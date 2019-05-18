# Molclass version 1.71
##### MolClass contains relevant pharmacological and physiological models to evaluate the performance of candidates in small molecule high throughput screens. Further it can build supervised machine learning models from small molecule datasets. It uses structural features and chemical properties identified in hit and non-hit molecule populations. It supports binary and multi class models. However the histogram display of models only displays two classes. IWe are planning to add regression models in the next release. 

## Folder Structure

build - contains the MolClass Java classes
dist - contains the MolClass.jar and depedencies needed to run MolClass from command line
html/molclass/api - contains the SLIM REST written in php5 
html/molclass/sdftools - tools that update and maintain the MolClass MySQL database
html/molclass/flask - contains the Python FLASK rest service 
src - contains the source code for MolClass 
lib - the new dependencies for MolClass version 1.5
nbproject - the Netbeans project configuration.


## Update May 2019 (version 1.71)
- MolClass is going to be moved to chemgrid.org/molclass as the original servers have been taken down due to age related instability.
- Because of memory limitations on chemgrid.org, model building will be restricted to libraries with up to thousand molecules.
- The instruction on how to install, run and use MolClas are being moved to the Github Wiki. https://github.com/jwildenhain/molclass/wiki/1.-MolClass-Wiki
- A virtual machine with MolClass is available on request.
- You can install the MolClass database and the FLASK REST service to access the data using R. 
- MolClass will get a supporting R package to use the current data models to design and benchmark your own activity predictions

## Update 17th September 2018
- New Models include Hepatocyte toxcicity, Log D prediction, Plasma Protein Binding, Solubility and Microsome toxcicity
- Molclass Wiki is being moved from the the MolClass server to GitHub. Find the Documentation on the Wiki tab.
- Extended REST features to extract compound information, structure, feature vectors, physiological properties and model predictions
- R snippets to retrieve MolClass REST results

The online version can be found on: http://sysbiolab.bio.ed.ac.uk/molclass/

## Update November 19th 2013
The new release has undergone some major improvements including a much faster structure search, new fingerprints, new machine
learning algorithms, an 85% similarity match and likelihood score distribution display. With the current version 1.5, 
biologically relevant Klekota-Roth fingerprints have been added to the learner. Further data is preclustered by Murcko-Fragments
and similarity calculated by enhanced connectivity fingerprints (ECFP) and Klekota-Roth fingerprints. The list of machine 
learners got extended including Bayesian and neuronal networks and majority vote/regression ensemble learning.

This repository contains the source and some of the dependencies. You can download the full software from the molclass server.

* For more details: http://sysbiolab.bio.ed.ac.uk/molclass/contact.php
* Installation guide for MolClass http://sysbiolab.bio.ed.ac.uk/wiki/index.php/MolClass#Installation_Guide
* The GitHub repository was automatically exported from code.google.com/p/molclass

