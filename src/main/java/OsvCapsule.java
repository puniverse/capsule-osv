/*
 * Capsule
 * Copyright (c) 2014-2015, Parallel Universe Software Co. and Contributors. All rights reserved.
 *
 * This program and the accompanying materials are licensed under the terms
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.AccessibleObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 *
 * @author pron
 */
public class OsvCapsule extends Capsule {

    private static final String CONF_FILE = "Capstanfile";

    private static final String PROP_JAVA_VERSION = "java.version";
    private static final String PROP_JAVA_HOME = "java.home";

    private static final String PROP_HYPERVISOR = "capsule.osv.hypervisor";

    private static final Path PATH_ROOT = Paths.get(File.separator);
    private static final Path PATH_APP = PATH_ROOT.resolve("capsule").resolve("app");
    private static final Path PATH_DEP = PATH_ROOT.resolve("capsule").resolve("dep");
    private static final Path PATH_WRAPPER = PATH_ROOT.resolve("capsule").resolve("wrapper");

    private static final Map.Entry<String, Boolean> ATTR_ONLY_BUILD_IMAGE = ATTRIBUTE("Image-Only", T_BOOL(), false, true, "Builds an image without launching the app.");
    private static final Map.Entry<String, String> ATTR_PORT_FORWARD = ATTRIBUTE("Port-Forward", T_STRING(), null, true, "Configure OSv port forwarding.");
    private static final Map.Entry<String, String> ATTR_NETWORK_TYPE = ATTRIBUTE("Network-Type", T_STRING(), null, true, "Configure OSv network type.");
    private static final Map.Entry<String, String> ATTR_PHYSICAL_NIC_NAME = ATTRIBUTE("Physical-NIC-Name", T_STRING(), null, true, "Configure OSv physical NIC name (f.e. mandatory under VirtualBox hypervisor).");

    private static Path hostAbsoluteOwnJarFile;

    private final Set<Path> deps = new HashSet<>();
    private Path localRepo;

    public OsvCapsule(Capsule pred) {
        super(pred);
    }

    /**
     * Resolve relative to the container
     */
    @Override
    protected Map.Entry<String, Path> chooseJavaHome() {
        Map.Entry<String, Path> res = super.chooseJavaHome();
        if (res == null)
            res = entry(getProperty(PROP_JAVA_VERSION), Paths.get(getProperty(PROP_JAVA_HOME)));
        return entry(res.getKey(), Paths.get(File.separator));
    }

    private static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected List<Path> resolve0(Object x) {
        if (x instanceof Path && ((Path) x).isAbsolute()) {
            Path p = (Path) x;
            if (localRepo != null && p.startsWith(localRepo))
                deps.add(p);
            p = move(p);
            return super.resolve0(p);
        }
        return super.resolve0(x);
    }

    @Override
    protected final Path getJavaExecutable() {
        return Paths.get("/java.so");
    }

    @Override
    protected final ProcessBuilder prelaunch(List<String> jvmArgs, List<String> args) {
        try {
            this.localRepo = getLocalRepo();

            final List<String> newJvmArgs = new ArrayList<>();
            newJvmArgs.addAll(jvmArgs);
            newJvmArgs.remove("-server");
            newJvmArgs.remove("-client");

            // Use the original ProcessBuilder to create the Capstanfile
            final ProcessBuilder pb = super.prelaunch(newJvmArgs, args);

            final boolean onlyBuild = getAttribute(ATTR_ONLY_BUILD_IMAGE);

            final String newCapstanFile = getCapstanfile(pb);
            if (isBuildNeeded(newCapstanFile)) {
                log(LOG_VERBOSE, "OSV image needs to be re-created");
                writeFile(newCapstanFile, getConfFile());
                log(LOG_VERBOSE, "Conf file written: " + getConfFile());

                if (!onlyBuild)
                    buildImage();
            }

            // ... and create a new ProcessBuilder to launch capstan
            final ProcessBuilder pb1 = new ProcessBuilder();
            pb1.directory(getConfDir().toFile());

            pb1.command().add("capstan");

            pb1.command().add(onlyBuild ? "onlyBuild" : "run");

            if (System.getProperty(PROP_HYPERVISOR) != null)
                pb1.command().addAll(Arrays.asList("-p", System.getProperty(PROP_HYPERVISOR)));

            if (getAttribute(ATTR_PORT_FORWARD) != null)
                pb1.command().addAll(Arrays.asList("-f", getAttribute(ATTR_PORT_FORWARD)));
            if (getAttribute(ATTR_NETWORK_TYPE) != null)
                pb1.command().addAll(Arrays.asList("-n", getAttribute(ATTR_NETWORK_TYPE)));
            if (getAttribute(ATTR_PHYSICAL_NIC_NAME) != null)
                pb1.command().addAll(Arrays.asList("-b", getAttribute(ATTR_PHYSICAL_NIC_NAME)));

            if (onlyBuild)
                pb1.command().add(getAppId());

            return pb1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void buildImage() throws IOException {
        log(LOG_VERBOSE, "Re-creating OSV image");

        final ProcessBuilder pb1 = new ProcessBuilder();
        pb1.directory(getConfDir().toFile());

        pb1.command().add("capstan");
        pb1.command().add("build");

        try {
            if (pb1.start().waitFor() != 0)
                throw new RuntimeException("Image build failed");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log(LOG_VERBOSE, "OSV image re-created");
    }

    public Path getConfDir() throws IOException {
        final Path ret = appDir().resolve("osv");
        if (!Files.exists(ret))
            Files.createDirectories(ret);
        return ret;
    }

    public Path getConfFile() throws IOException {
        return getConfDir().resolve(CONF_FILE);
    }

    private boolean isBuildNeeded(String capstanFile) throws IOException {
        // Check if the conf file exists
        if (!Files.exists(getConfFile())) {
            log(LOG_VERBOSE, "Conf file " + getConfFile() + " is not present");
            return true;
        }

        // Check if the conf content has changed
        if (!new String(Files.readAllBytes(getConfFile()), Charset.defaultCharset()).equals(capstanFile)) {
            log(LOG_VERBOSE, "Conf file content " + getConfFile() + " has changed");
            return true;
        }

        // Check if the application is newer
        try {
            FileTime jarTime = Files.getLastModifiedTime(getJarFile());
            if (isWrapperCapsule()) {
                final FileTime wrapperTime = Files.getLastModifiedTime(findOwnJarFile());
                if (wrapperTime.compareTo(jarTime) > 0)
                    jarTime = wrapperTime;
            }

            final FileTime confTime = Files.getLastModifiedTime(getConfFile());

            final boolean buildNeeded = confTime.compareTo(jarTime) < 0;
            if (buildNeeded)
                log(LOG_VERBOSE, "Application " + getJarFile() + " has changed");
            return buildNeeded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected List<Path> getPlatformNativeLibraryPath() {
        return splitClassPath("/usr/java/packages/lib/amd64:/usr/lib64:/lib64:/lib:/usr/lib");
    }

    private String getCapstanfile(ProcessBuilder pb) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("base: ").append(getBaseImage()).append("\n\n");
        sb.append("cmdline: ").append(getCommand(pb)).append("\n\n");
        sb.append("files:\n");
        sb.append(file(getJarFile())).append("\n");
        final Path appDir = appDir();
        if (appDir != null) {
            for (Path p : listDir(appDir, "**", true))
                sb.append(file(p)).append("\n");
        }
        for (Path p : deps)
            sb.append(file(p)).append("\n");
        return sb.toString();
    }

    private void writeFile(String content, Path loc) throws IOException {
        try (final PrintWriter out = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(loc), Charset.defaultCharset()))) {
            out.print(content);
        }
    }

    private String getCommand(ProcessBuilder pb) {
        final List<String> command = new ArrayList<>();
        command.addAll(pb.command());
        command.remove("-server");
        command.remove("-client");
        final Iterator<String> iter = command.iterator();
        final StringBuilder sb = new StringBuilder();
        if (iter.hasNext()) {
            sb.append(iter.next());
            while (iter.hasNext()) {
                sb.append(" ").append(iter.next());
            }
        }
        return sb.toString();
    }

    private String file(Path p) {
        return "  " + move(p) + ": " + p;
    }

    private Path move(Path p) {
        if (p == null)
            return null;

        p = p.normalize().toAbsolutePath();

        if (p.startsWith(Paths.get("/dep")) || p.startsWith(Paths.get("/app")))
            return p;

        if (p.equals(getJavaExecutable().toAbsolutePath()))
            return PATH_ROOT.resolve(getJavaExecutable());
        if (p.equals(getJarFile()))
            return moveJarFile(p);
        if (p.equals(findOwnJarFile()))
            return moveWrapperFile(p);
        else if (appDir() != null && p.startsWith(appDir()))
            return move(p, appDir(), PATH_APP);
        else if (localRepo != null && p.startsWith(localRepo))
            return move(p, localRepo, PATH_DEP);
        else if (getPlatformNativeLibraryPath().contains(p))
            return p;
        else if (p.startsWith(getJavaHome()))
            return p; // already moved in chooseJavaHome
        else
            throw new IllegalArgumentException("Unexpected file " + p);
    }

    private Path moveJarFile(Path p) {
        return PATH_ROOT.resolve(p.getFileName());
    }

    private Path moveWrapperFile(Path p) {
        return PATH_WRAPPER.resolve(p.getFileName());
    }

    private String getBaseImage() {
        if (hasAttribute(Capsule.ATTR_JAVA_VERSION)) {
            switch (javaVersion(getAttribute(Capsule.ATTR_JAVA_VERSION))) {
                case 7:
                    return "cloudius/osv-openjdk";
                case 8:
                    return "cloudius/osv-openjdk8";
                default:
                    // getJavaHome(); ...
                    throw new RuntimeException("No known OSv image for Java version " + getAttribute(Capsule.ATTR_JAVA_VERSION));
            }
        } else
            return "cloudius/osv-openjdk8";
    }

    // TODO Factor out this common utilities
    //<editor-fold defaultstate="collapsed" desc="Both capsule- and container-related overrides & utils">
    private Path getLocalRepo() {
        final Capsule mavenCaplet = sup("MavenCapsule");
        if (mavenCaplet == null)
            return null;
        try {
            return (Path) accessible(mavenCaplet.getClass().getDeclaredMethod("getLocalRepo")).invoke(mavenCaplet);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T extends AccessibleObject> T accessible(T obj) {
        if (obj == null)
            return null;
        obj.setAccessible(true);
        return obj;
    }

    private static Path findOwnJarFile() {
        if (hostAbsoluteOwnJarFile == null) {
            final URL url = OsvCapsule.class.getClassLoader().getResource(OsvCapsule.class.getName().replace('.', '/') + ".class");
            if (url != null) {
                if (!"jar".equals(url.getProtocol()))
                    throw new IllegalStateException("The Capsule class must be in a JAR file, but was loaded from: " + url);
                final String path = url.getPath();
                if (path == null) //  || !path.startsWith("file:")
                    throw new IllegalStateException("The Capsule class must be in a local JAR file, but was loaded from: " + url);

                try {
                    final URI jarUri = new URI(path.substring(0, path.indexOf('!')));
                    hostAbsoluteOwnJarFile = Paths.get(jarUri);
                } catch (URISyntaxException e) {
                    throw new AssertionError(e);
                }
            } else
                throw new RuntimeException("Can't locate capsule's own class");
        }
        return hostAbsoluteOwnJarFile;
    }

    private static int javaVersion(String v) {
        final String[] vs = v.split("\\.");
        if (vs.length == 1) {
            if (Integer.parseInt(vs[0]) < 5)
                throw new RuntimeException("Unrecognized major Java version: " + v);
            return Integer.parseInt(vs[0]);
        } else
            return Integer.parseInt(vs[1]);
    }

    private static List<Path> splitClassPath(String classPath) {
        final String[] ps = classPath.split(":");
        final List<Path> res = new ArrayList<>(ps.length);
        for (String p : ps)
            res.add(Paths.get(p));
        return res;
    }

    protected static List<Path> listDir(Path dir, String glob, boolean regular) {
        //noinspection Convert2Diamond
        return listDir(dir, glob, false, regular, new ArrayList<Path>());
    }

    private static List<Path> listDir(Path dir, String glob, boolean recursive, boolean regularFile, List<Path> res) {
        return listDir(dir, splitGlob(glob), recursive, regularFile, res);
    }

    private static List<String> splitGlob(String glob) { // splits glob pattern by directory
        return glob != null ? Arrays.asList(glob.split("\\".equals(File.separator) ? "\\\\" : File.separator)) : null;
    }

    @SuppressWarnings({"null", "Convert2Diamond"})
    private static List<Path> listDir(Path dir, List<String> globs, boolean recursive, boolean regularFile, List<Path> res) {
        PathMatcher matcher = null;
        if (globs != null) {
            while (!globs.isEmpty() && "**".equals(globs.get(0))) {
                recursive = true;
                globs = globs.subList(1, globs.size());
            }
            if (!globs.isEmpty())
                matcher = dir.getFileSystem().getPathMatcher("glob:" + globs.get(0));
        }

        final List<Path> ms = (matcher != null || recursive) ? new ArrayList<Path>() : res;
        final List<Path> mds = matcher != null ? new ArrayList<Path>() : null;
        final List<Path> rds = recursive ? new ArrayList<Path>() : null;

        try (final DirectoryStream<Path> fs = Files.newDirectoryStream(dir)) {
            for (Path f : fs) {
                if (recursive && Files.isDirectory(f))
                    rds.add(f);
                if (matcher == null) {
                    if (!regularFile || Files.isRegularFile(f))
                        ms.add(f);
                } else {
                    if (matcher.matches(f.getFileName())) {
                        if (globs.size() == 1 && (!regularFile || Files.isRegularFile(f)))
                            ms.add(f);
                        else if (Files.isDirectory(f))
                            mds.add(f);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Collections.sort(ms); // sort to give same results on all platforms (hopefully)
        if (res != ms) {
            res.addAll(ms);

            //noinspection UnusedLabel
            recurse:
                for (final List<Path> ds : Arrays.asList(mds, rds)) {
                    if (ds == null)
                        continue;
                    Collections.sort(ds);
                    final List<String> gls = (ds == mds ? globs.subList(1, globs.size()) : globs);
                    for (final Path d : ds)
                        listDir(d, gls, recursive, regularFile, res);
                }
        }

        return res;
    }
    //</editor-fold>
}
