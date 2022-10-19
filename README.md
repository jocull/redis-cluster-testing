What is this?
================

Dockerized Redis clusters and a small chaos testing app. The purpose of this is to test failure modes with real Redis clusters in a local environment to check them for failure mode and consistency issues.

Due to the implementation of Redis Cluster, the only way I could find to attach the app to the network was to be inside the network itself. The application exposes a remote JVM debugging port so you can still debug it locally, set breakpoints, etc.

- Docker compose testing application in root of repo
- Docker compose first cluster in `/cluster-a`
- Docker compose second cluster in `/cluster-b`

See [`notes.md`](notes.md) for some example commands to join the cluster nodes together. It's a very manual process.
