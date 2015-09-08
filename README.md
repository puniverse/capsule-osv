# Capsule OSv

A [caplet](https://github.com/puniverse/capsule#what-are-caplets) that wraps a [capsule](https://github.com/puniverse/capsule) in an [OSv](http://osv.io/) image.

## Requirements

In addition to [Capsule's](https://github.com/puniverse/capsule):

  * [Capstan](http://osv.io/capstan/) correctly installed.

## Usage

The Gradle-style dependency you need to embed in your Capsule JAR, which you can generate with the tool you prefer (f.e. with plain Maven/Gradle as in [Photon](https://github.com/puniverse/photon) and [`capsule-gui-demo`](https://github.com/puniverse/capsule-gui-demo) or higher-level [Capsule build plugins](https://github.com/puniverse/capsule#build-tool-plugins)), is `co.paralleluniverse:capsule-osv:0.1.0`. Also include the caplet class in your Capsule manifest, for example:

``` gradle
    Caplets: MavenCapsule OsvCapsule
```

`capsule-osv` can also be run as a wrapper capsule without embedding it:

``` bash
$ java -Dcapsule.log=verbose -jar capsule-osv-0.1.0.jar my-capsule.jar my-capsule-arg1 ...
```

It can be both run against (or embedded in) plain (e.g. "fat") capsules and [Maven-based](https://github.com/puniverse/capsule-maven) ones.

## Additional Capsule manifest entries

The following additional manifest entries and system properties can be used (see the [Capstan docs](https://github.com/cloudius-systems/capstan#capstan) for further details):

 * `capsule.osv.buildOnly` system property: builds an image without launching the app (default: false).
 * `capsule.osv.hypervisor` system property: allows to specify a non-default hypervisor to be used by OSV (default: KVM on Linux).
 * `Port-Forward` manifest entry: configure OSv port forwarding (default: none). The `capsule.osv.portForward` system property can override it.
 * `Network-Type`: configure OSv network type (default: user networking on KVM). The `capsule.osv.networkType` system property can override it.
 * `Physical-NIC-Name`: configure OSv physical NIC name (needed with VirtualBox). The `capsule.osv.physicalNICName` system property can override it.

## Known caveats

At present Java agents don't work (see [this OSv issue](https://github.com/cloudius-systems/osv/issues/528)).

## License

    MIT
