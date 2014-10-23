import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Copyright (c) 2014, Parallel Universe Software Co. and Contributors. All rights reserved.
 * 
 * This program and the accompanying materials are licensed under the terms 
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
/**
 *
 * @author pron
 */
public class OsvCapsule extends Capsule {
    private static final String CONF_FILE = "Capstanfile";

    private static final String PROP_FILE_SEPARATOR = "file.separator";
    private static final String PROP_BUILD_IMAGE = "capsule.osv.build";
    private static final String PROP_HYPERVISOR = "capsule.osv.hypervisor";

    private static final String ATTR_JAVA_VERSION = "Java-Version";
    private static final String ATTR_MIN_JAVA_VERSION = "Min-Java-Version";
    private static final String ATTR_MIN_UPDATE_VERSION = "Min-Update-Version";

    private static final String PATH_ROOT = "/";
    private static final String PATH_APP = PATH_ROOT + "app";
    private static final String PATH_DEP = PATH_ROOT + "dep";

    private static final String FILE_SEPARATOR = System.getProperty(PROP_FILE_SEPARATOR);

    private final Path localRepo;
    private final Set<Path> deps = new HashSet<>();
    private Path confDir;

    public OsvCapsule(Path jarFile, Path cacheDir) {
        super(jarFile, cacheDir);
        this.localRepo = getLocalRepo();
    }

    @Override
    protected String processOutgoingPath(Path p) {
        if (p == null)
            return null;
        p = p.normalize().toAbsolutePath();
        if (p.startsWith(localRepo))
            deps.add(p);
        return move(p);
    }

    @Override
    protected final Path getJavaExecutable() {
        return Paths.get("java.so");
    }

    @Override
    protected ProcessBuilder buildProcess() {
        final boolean build = systemPropertyEmptyOrTrue(PROP_BUILD_IMAGE);
        try {
            // Use the original ProcessBuilder to create the Capstanfile
            final ProcessBuilder pb = super.buildProcess();
            this.confDir = createConfDir();
            writeCapstanfile(confDir.resolve(CONF_FILE), pb);

            log(LOG_VERBOSE, "Capstanfile: " + confDir.resolve(CONF_FILE));

            // ... and create a new ProcessBuilder to launch capstan
            final ProcessBuilder pb1 = new ProcessBuilder();
            pb1.directory(confDir.toFile());

            pb1.command().add("capstan");
            pb1.command().add(build ? "build" : "run");
            if (System.getProperty(PROP_HYPERVISOR) != null)
                pb1.command().addAll(Arrays.asList("-p", System.getProperty(PROP_HYPERVISOR)));
            if (build)
                pb1.command().add(getAppId());

            return pb1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Map<String, String> buildEnvironmentVariables(Map<String, String> env) {
        return env;
    }

    @Override
    protected List<String> buildJVMArgs() {
        final List<String> args = super.buildJVMArgs();
        args.remove("-server");
        args.remove("-client");
        return args;
    }

    @Override
    protected List<Path> getPlatformNativeLibraryPath() {
        return Arrays.asList(Paths.get("/usr/java/packages/lib/amd64"), Paths.get("/usr/lib64"), Paths.get("/lib64"), Paths.get("/lib"), Paths.get("/usr/lib"));
    }

    private void writeCapstanfile(Path file, ProcessBuilder pb) throws IOException {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(file), Charset.defaultCharset()))) {
            out.println("base: " + getBaseImage());

            out.println("files:");
            out.println(file(getJarFile()));
            if (getAppCache() != null) {
                for (Path p : listDir(getAppCache(), "**", true))
                    out.println(file(p));
            }
            for (Path p : deps)
                out.println(file(p));

            out.println("cmdline: " + toStringValue(pb.command()));
        }
    }

    private String file(Path p) {
        return "  " + move(p) + ": " + p;
    }

    private String move(Path p) {
        p = p.normalize().toAbsolutePath();
        if (p.equals(getJavaExecutable().toAbsolutePath()))
            return PATH_ROOT + getJavaExecutable();
        if (p.equals(getJarFile()))
            return moveJarFile(p);
        else if (getAppCache() != null && p.startsWith(getAppCache()))
            return moveAppCache(p);
        else if (p.startsWith(localRepo))
            return moveDep(p);
        else if (getPlatformNativeLibraryPath().contains(p))
            return toString(p);
        else
            throw new IllegalArgumentException("Unexpected file " + p);
    }

    private String moveJarFile(Path p) {
        return PATH_ROOT + p.getFileName();
    }

    private String moveAppCache(Path p) {
        return move(p, getAppCache(), PATH_APP);
    }

    private String moveDep(Path p) {
        return PATH_DEP + "/" + p.getFileName();
    }

    private Path createConfDir() throws IOException {
        Path temp = Files.createTempDirectory("osv-");
        Path dir = temp.resolve(getAppId());
        Files.createDirectory(dir);
        return dir;
    }

    private void deleteConfDir() throws IOException {
        Files.delete(confDir.resolve(CONF_FILE));
        Files.delete(confDir);
        Files.delete(confDir.getParent());
    }

    @Override
    @SuppressWarnings("CallToPrintStackTrace")
    protected void cleanup() {
        super.cleanup();

        try {
            if (confDir != null)
                deleteConfDir();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String move(Path what, Path fromDir, String toDir) {
        assert what.startsWith(fromDir);
        return toDir + "/" + toString(fromDir.relativize(fromDir));
    }

    private static String toString(Path p) {
        return isWindows() ? p.toString().replace(FILE_SEPARATOR, "/") : p.toString();
    }

    private String getBaseImage() {
        if (hasAttribute(ATTR_JAVA_VERSION)) {
            switch (javaVersion(getAttribute(ATTR_JAVA_VERSION))) {
                case 7:
                    return "cloudius/osv-openjdk";
                case 8:
                    return "cloudius/osv-openjdk8";
                default:
                    // getJavaHome(); ...
                    throw new RuntimeException("No known OSv image for Java version " + getAttribute(ATTR_JAVA_VERSION));
            }
        } else
            return "cloudius/osv-openjdk8";
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

    private static boolean systemPropertyEmptyOrTrue(String property) {
        final String value = System.getProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || Boolean.parseBoolean(value);
    }
}
