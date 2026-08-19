# Weka SMOTE 1.0.3

- Upstream: `https://prdownloads.sourceforge.net/weka/SMOTE1.0.3.zip?download`
- Package metadata: `https://weka.sourceforge.io/packageMetaData/SMOTE/1.0.3.html`
- Upstream ZIP SHA-256: `a2ed76cabf07f2de1c0c4d91e3c18778a2c061f5ce7eb02cd1ad1196c7e81df4`
- License declared by upstream: GPL 3.0
- Release date: 2013-03-07

MolClass previously bundled an older SMOTE JAR created by Ant 1.7.0. It throws `Comparison method violates its general contract` from `SMOTE.doSMOTE` on Java 21. Upstream 1.0.3 explicitly fixes that Java sorting defect. `lib_legacy/SMOTE.jar` is retained unchanged as migration provenance; `lib/SMOTE.jar` is the pinned runtime dependency.
