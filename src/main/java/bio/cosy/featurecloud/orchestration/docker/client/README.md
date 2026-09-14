# Code Information

This code is copied from https://github.com/quarkiverse/quarkus-docker-client as the latest upgrade is half a year old.
To keep up with the latest Docker upgrades (e.g., new API features and security patches), this code has been vendored
into this project.

## Purpose

This folder contains Docker client code that enables container orchestration functionality within the FeatureCloud
platform. It provides the necessary integration to manage Docker containers, including:

- Starting and stopping containers
- Monitoring container logs
- Managing container lifecycle
- Handling network and resource configurations

## Why This Code is Here

The original `quarkus-docker-client` project has not been updated recently, but we need to stay current with Docker API
changes and improvements. By maintaining this code locally, we can:

- Ensure compatibility with the latest Docker versions
- Apply custom modifications as needed for our use case
- Maintain stability and control over our Docker integration

## Usage

This Docker client is used throughout the orchestration layer, particularly in:

- `ContainerServiceImpl.java` - Main service for container operations
- `ContainerAppBO.java` and `ContainerPipelineBO.java` - Business logic for container management
- Other orchestration components that interact with Docker

## Maintenance

When updating this code, please:

1. Document any changes made from the original source
2. Test thoroughly with the current Docker version in use
3. Update this README with relevant information about modifications