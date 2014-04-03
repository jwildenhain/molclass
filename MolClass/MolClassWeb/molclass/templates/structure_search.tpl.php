
  <h2>
    {$title}
  </h2>

<table cellpadding="2" cellspacing="2" border="0" width="100%">
  <tr>
    <td>
      <div name="JME" code="JME.class" codebase="./classes" archive="JME.jar" width="400" height="300">
      <param name="jme" value="$jme">
      <param name="options" value="xbutton, hydrogens">
      You have to enable JavaScript in your browser.
      </div>
    </td>
    <td>
      <small>
        special symbols (to be entered via X-button):<br /> 
        <b>A</b>: any atom except H<br />
        <b>Q</b>: any atom except H and C<br />
        <b>X</b>: any halogen atom<br />
        <b>H</b>: explicit hydrogen<br />
        <br />
        <a href="http://www.molinspiration.com/jme/">JME Editor</a>
        courtesy of Peter Ertl, Novartis
        <br />
        <br />
        <a href="http://merian.pch.univie.ac.at/~nhaider/cheminf/moldb5r.html">moldb5r</a>
        courtesy of Norbert Haider, University of Vienna

        <br />
        <br />
<!--        <form name="molform">text input form<br>(MDL molfile format):
        <input type="button" value="Open" onClick="opentextwindow();">
        </form>
-->
      </small>
    </td>
  </tr>
</table>
<form name="form" action={$phpself} method="post">
<input type="radio" name="mode" value="1" checked>exact search
<input type="radio" name="mode" value="2" >substructure search
<input type="radio" name="mode" value="3" >similarity search,
 <small>using a structural:functional similarity ratio of 
 <select size = "1" name="fsim">
 <option value="0.0" >100:0</option>
 <option value="0.1" >90:10</option>
 <option value="0.2" >80:20</option>
 <option value="0.3" >70:30</option>
 <option value="0.4" >60:40</option>
 <option value="0.5" selected>50:50</option>
 <option value="0.6  >40:60</option>
 <option value="0.7" >30:70</option>
 <option value="0.8" >20:80</option>
 <option value="0.9" >10:90</option>
 <option value="1.0" >0:100</option>
 </select>
 </small
<br>
<br>
<input type="checkbox" name="strict" value="y">strict atom/bond type comparison<br />
<input type="checkbox" name="stereo" value="y">check configuration (E/Z and R/S)<br />
<h4> &nbsp;Search by InChi, InChiKey, Smiles or molecule identifier:</H4>
<input type="text" name="structuretextsearch" size="61" >&nbsp;&nbsp;&nbsp;<br />&nbsp;<br />

<script> 
  function check_ss() {
    var smiles = document.JME.smiles();
    var jme = document.JME.jmeFile(); 
    var mol = document.JME.molFile();
    if (smiles.length < 1) {
      alert("No molecule! If you are using the text search option add a single click draw and you will be able to execute the search. We are currently reviewing options to change this behaviour.");
    }
    else {
      document.form.smiles.value = smiles;
      document.form.jme.value = jme;
      document.form.mol.value = mol;
      var info = document.referrer;
      info += " - " + navigator.appName + " - " + navigator.appVersion;
      info += " " + screen.width + "x" + screen.height;
      document.form.rinfo.value = info;
      document.form.submit();
    }
  }
  function putmoltext(moltext) {
    if (moltext.length < 1) {
      alert("No molecule!");
      return;
    }
    else {
      document.JME.readMolFile(moltext);
    }    
  }
  function showmoltext() {
    var moltext=document.JME.molFile();
    if (moltext.length < 1) {
      alert("No molecule!");
    }
    else {
      alert(moltext);
    }    
  }
  function getmoltext() {
    var moltext = document.JME.molFile();
    return moltext;
  }

function saveJMECookie() {
  var jme = document.JME.jmeFile();
  document.cookie = "jme="+jme+";expires=Thu, 31 Dec 2020 00:00:00 GMT; path=/";
}

function readJMECookie() {
  var editor = document.JME;
  if (editor.smiles().length > 0) return; // editing already started
  var ca = document.cookie.split(';');
  for(var i=0;i < ca.length;i++) {
    var c = ca[i];
    while (c.charAt(0)==' ') c = c.substring(1,c.length);
    if (c.indexOf("jme=") == 0) {
      var jme = c.substring(4,c.length);
      editor.readMolecule(jme);
      return;
    }
  }
}

function substituent(r) {
  document.JME.setSubstituent(r);
}

  function opentextwindow() {
    window.open('txtinp.php','input','width=600,height=450,scrollbars=no,resizable=yes');
  }

</script>

<!--
<script> 
  function check_ss() {
    var smiles = document.JME.smiles();
    var jme = document.JME.jmeFile(); 
    var mol = document.JME.molFile();
    if (smiles.length < 1) {
      alert("No molecule! If you are using the text search option add a single click draw and you will be able to execute the search. We are currently reviewing options to change this behaviour.");
    }
    else {
      document.form.smiles.value = smiles;
      document.form.jme.value = jme;
      document.form.mol.value = mol;
      var info = document.referrer;
      info += " - " + navigator.appName + " - " + navigator.appVersion;
      info += " " + screen.width + "x" + screen.height;
      document.form.rinfo.value = info;
      document.form.submit();
    }
  }
</script>
-->

<input type="button" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;" onClick="check_ss()">
<input type="hidden" name="smiles">
<input type="hidden" name="jme">
<input type="hidden" name="mol">
<input type="hidden" name="rinfo">
<input type="hidden" name="db" value="1">
</form>


 
