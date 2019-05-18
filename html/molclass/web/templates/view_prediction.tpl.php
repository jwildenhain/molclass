
  <h2>
    {$title} <a href=excelexport.php?pred_id={$pred_id}>xls</a>
  </h2>
  <table class="geneform">
    <th>
      Prediction ID
    </th>
    <th>
      Batch ID
    </th>
    <th>
      Model ID
    </th>
    <th>
      Classes
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
          <a href=view_prediction.php?pred_id={$contents[batch].0}>{$contents[batch].0}</a>
        </td>
        <td>
          <a href=view_batch_detail.php?batch_id={$contents[batch].2}>{$contents[batch].2}</a>
        </td>
        <td>
          <a href=view_model_detail.php?model_id={$contents[batch].3}>{$contents[batch].3}</a>
        </td>
        <td>
          {$contents[batch].4}
        </td>
        <td>
          {$contents[batch].5}
        </td>
        <td>
          {$contents[batch].6}
        </td>
        <td>
          {$contents[batch].7}
        </td>
      <tr>
    {/section}
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
    <a href="#" title="See Class Statistics" onclick="showPlagin(1);return false;">[+] See Prediction</a>
  </p>
</div>

<div id="PlagClose1" style="display: none">
  <p>
    <a href="#" title="Fold" onclick="showPlagin(1);return false;">[-] Fold</a>
  </p>
  <br />
  <p>{$prediction}</p>

</div>
<br />
