
  <h2>
    {$title}
  </h2>
  <table class="geneform">
    <th>
      Model ID
    </th>
    <th>
      Classes
    </th>
    <th>
      Batch ID
    </th>
    <th>
      Data Type
    </th>
    <th>
      Class Tag
    </th>
    <th>
      Class Scheme
    </th>
    {section name=batch loop=$contents}
      <tr>
        <td>
          <a href=view_model_detail.php?model_id={$contents[batch].0}>{$contents[batch].0}</a>
        </td>
        <td>
          {$contents[batch].4}
        </td>
        <td>
          <a href=view_batch_detail.php?batch_id={$contents[batch].6}>{$contents[batch].6}</a>
        </td>
        <td>
          {$contents[batch].7}
        </td>
        <td>
          {$contents[batch].8}
        </td>
        <td>
          {$contents[batch].9}
        </td>
      <tr>
    {/section}
  </table>
  <h3>
    {$learn}
  </h3>
  <table class="geneform">
    <th>
      PubMed ID
    </th>
    <th>
      Description
    </th>
    <tr>
      <td>
        <a href=http://www.ncbi.nlm.nih.gov/pubmed/{$pmid}>{$pmid}</a>
      </td>
      <td>
        {$info}
      </td>
    </tr>
  </table>

  <br />
<script type="text/javascript">
<!--
  function showPlagin(idno){
    pc = ('PlagClose' + (idno));
    po = ('PlagOpen' + (idno));
    if( document.getElementById(pc).style.display == "none" ) {
      document.getElementById(pc).style.display = "block";
      document.getElementById(po).style.display = "none";
    }
    else {
      document.getElementById(pc).style.display = "none";
      document.getElementById(po).style.display = "block";
    }
  }
//-->
</script>

<div id="PlagOpen1">
  <p>
    <a href="#" title="See Model Statistics" onclick="showPlagin(1);return false;">[+] See Model Statistics</a>
  </p>
</div>

<div id="PlagClose1" style="display: none">
  <p>
    <a href="#" title="Fold" onclick="showPlagin(1);return false;">[-] Fold</a>
  </p>
  <br />
  <p>{$statistics}</p>

</div>

  <h4>
    {$search}
  </h4>

  <form name="form" action={$phpself} method="get">
  Prediction ID : <input type="text" name="pred_id">
  Batch ID : <input type="text" name="batch_id">
  <br />
  <input type="hidden" name="model_id" value="{$model_id}" />  
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>
  <br />

