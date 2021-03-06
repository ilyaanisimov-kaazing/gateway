# Gateway

[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/kaazing/gateway?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status][build-status-image]][build-status]

[build-status-image]: https://travis-ci.org/kaazing/gateway.svg?branch=develop
[build-status]: https://travis-ci.org/kaazing/gateway

# About this Project

The Gateway is a network gateway created to provide a single access point for real-time web based protocol elevation that supports load balancing, clustering, and security management.  It is designed to provide scalable and secure bidirectional event-based communication over the web; on every platform, browser, and device.    

A set of client APIs are provided in the following repos. 

- [JavaScript SDK for WebSocket](https://github.com/kaazing/kaazing-client-javascript)
- [Objective-C SDK for WebSocket](https://github.com/kaazing/gateway.client.ios)
- [Android and Java SDK for WebSocket](https://github.com/kaazing/gateway.client.java)
- [JavaScript SDK for AMQP 0-9-1](https://github.com/kaazing/kaazing-amqp-0-9-1-client-javascript)
- [Java SDK for AMQP 0-9-1](https://github.com/kaazing/amqp.client.java)

# Running via public Docker Image

Edit `/etc/hosts` file (or equivalent) to set gateway as the Docker host

`docker run --rm -p 8000:8000 -h gateway kaazing/gateway:latest`

Point your browser at `http://gateway:8000` to see the WebSocket demo.

See Kaazing Gateway on [Docker Hub](https://registry.hub.docker.com/u/kaazing/gateway/) for more information.

# Building this Project

## Requirements for building the project
* Java 8 JDK or higher
* Apache Maven 3.0.5 or higher, with the recommended settings:

  `MAVEN_OPTS="-Xms768m -Xmx768m -XX:MaxPermSize=768m"`

## Steps for building this project

To build the Gateway locally, use:

  `mvn clean install`

### Building the Docker container locally

To build a Docker container locally:

  `mvn clean install -Pdocker`

Then to start the container, use:

  `docker run --rm -p 8080:8080 -h gateway kaazing/unstable-gateway:develop-SNAPSHOT`

Make sure you set `/etc/hosts` file (or equivalent) to set gateway as the Docker host.

`192.168.2.100  dockerhost gateway`

Then point your browser at `http://gateway:8000`

For more information see the [docker](docker) directory.

# Running this Project

0. `cd distribution/target`
1. Unpack the appropriate distribution
   
   Mac/Linux: `tar -xvf kaazing-gateway-community-develop-SNAPSHOT.tar.gz`
   
   Windows: `unzip kaazing-gateway-community-develop-SNAPSHOT.zip`
2. Start the Gateway 
   
   Mac/Linux: `./kaazing-gateway-community-develop-SNAPSHOT/bin/gateway.start`
   
   Windows: `./kaazing-gateway-community-develop-SNAPSHOT/bin/gateway.start.bat`

# Running a Prebuilt Project

You can also download and run this project from [kaazing.org/download](http://kaazing.org/download/)

# Developing Client Applications

To learn how to develop client applications using the Gateway, see the documentation on [kaazing.org](http://kaazing.org).

# See a Live Demo

To view demo client applications running against the Gateway, visit [kaazing.org/demos](http://kaazing.org/demos/).

# Contact

The [Gateway Gitter room](https://gitter.im/kaazing/gateway) is the easiest way to contact the developers who maintain the project.

