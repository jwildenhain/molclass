  <div style="float:left; ">
  <h2>
    {$title}
  </h2>
    
    <b>Name :</b> {$name[0]}<br/>
    <br/>
    
    <b>Smiles :</b> {$smiles[0]}<br/>
    <b>InChI Key :</b> {$inchi_key[0]}<br/>
    <b>Molecular Weight :</b> {$MW[0]}<br/>
    <b>Rotatable Bonds :</b> {$nRotB[0]}<br/>
    <b>HBond Acceptors :</b>  {$nHBAcc[0]}<br/>
    <b>HBond Donors :</b> {$nHBDon[0]}<br/>
    <b>XLogP :</b> {$XLogP[0]}<br/>
    <b>Lipinski Failures:</b> {$LipinskiFailures[0]}<br/>
    <br>
    <b><a href="http://www.ncbi.nlm.nih.gov/sites/entrez?db=pccompound&term={$inchi_key[0]}">In PubChem</a></b> |
    <b><a href=excelexport.php?mol_id={$mol_id[0]}>Data as xls</a></b> |
    <b><a href=molecule_detail.php?mol_id={$mol_id[0]}&models=2,4>Pharmacology</a></b> |
    <b><a href=molecule_detail.php?mol_id={$mol_id[0]}&models=1,2>Microbial</a></b> |
    <b><a href=molecule_detail.php?mol_id={$mol_id[0]}>All</a></b> |
    <b><a href=hit_molecules.php?tanimoto={$mol_id[0]}>Similars</a></b>
  </div>

  <div style="left: 50px; top:13px; position: relative; ">
  <img src="{$picture}">
  
  </div>

  
  <br/>

  
