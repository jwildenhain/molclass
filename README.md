# MolClass version 2.0.0
##### MolClass contains relevant pharmacological and physiological models to evaluate the performance of candidates in small molecule high throughput screens. Further it can build supervised machine learning models from small molecule datasets. It uses structural features and chemical properties identified in hit and non-hit molecule populations. It supports binary and multi class models. However the histogram display of models only displays two classes. We are planning to add regression models in the next release. 

## Folder Structure
```
build - contains the compiled MolClass Java classes
dist - contains the MolClass.jar and dependencies needed to run MolClass from command line
html/molclass/api - contains the unified FastAPI REST service in Python 3
html/molclass/tools - tools that update and maintain the MolClass MySQL database
html/molclass/web - php5/pear webapplication (running on Ubuntu 14.04 LTS)
src - contains the Java source code for MolClass 
lib - dependencies for MolClass version 2.0.0
nbproject - the Netbeans project configuration.
```

## Update June 2026 (version 2.0.0)
- **Library Upgrades**: Upgraded core chemistry and machine learning dependencies:
  - Chemistry Development Kit (CDK) upgraded from version 1.4 to `cdk-2.12.jar`.
  - Weka machine learning library upgraded from 3.6 to `weka-stable-3.8.6.jar`.
- **API Modernization**: Consolidated the PHP Slim and Python Flask REST APIs into a single, high-performance **Python FastAPI** service with SQLAlchemy connection pooling, request-scoped sessions, Pydantic response validation, and automated Swagger OpenAPI documentation.
- **Performance & Search Optimizations**:
  - Replaced legacy $O(N)$ index-unfriendly database query patterns with a highly optimized, index-covered `UNION` lookup for molecules, accelerating compound searches by **~20,000x**.
  - Optimized `ModelBuilder` feature selection to use **forward search** instead of backward search, reducing execution time for high-dimensional feature spaces (e.g. `JUMBO` with ~7,900 features) from hours/days to **under 20 seconds**.
  - Added XML configuration cache to eliminate redundant disk I/O.
- **Multithreading**: Rewrote fingerprinters and similarity calculators to support thread-safe parallel calculations utilizing thread-local database connections and configurable thread pools.
- **Verification**: Built a comprehensive automated JUnit test suite validating all 15 classifier schemes, descriptors, scaffolds, and parallel pipelines.

## Update May 2019 (version 1.71)
- MolClass is going to be moved to chemgrid.org/molclass as the original servers have been taken down due to age related instability.
- Because of memory limitations on chemgrid.org, model building will be restricted to libraries with up to thousand molecules.
- The instruction on how to install, run and use MolClass are being moved to the Github Wiki. https://github.com/jwildenhain/molclass/wiki/1.-MolClass-Wiki
- A virtual machine with MolClass is available on request.
- You can install the MolClass database and the FLASK REST service to access the data using R. 
- MolClass will get a supporting R package to use the current data models to design and benchmark your own activity predictions

