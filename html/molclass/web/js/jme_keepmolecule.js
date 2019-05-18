
function saveCookie() {
  var jme = document.JME.jmeFile();
  document.cookie = "jme="+jme+";expires=Thu, 31 Dec 2020 00:00:00 GMT; path=/";
}

function readCookie() {
  var editor = document.JME;
  if (editor.smiles().length > 0) return; // editing already started
  var ca = document.cookie.split(';');
  for(var i=0;i < ca.length;i++) {
    var c = ca[i];
    while (c.charAt(0)==' ') c = c.substring(1,c.length);
    if (c.indexOf("jme=") == 0) {
      var jme = c.substring(4,c.length);
      //alert("jme="+jme);
      editor.readMolecule(jme);
      return;
    }
  }
}


