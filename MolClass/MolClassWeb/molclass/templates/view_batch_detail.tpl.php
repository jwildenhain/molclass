
  <h2>
      {$title} More details: <a href={$wikilink}{$batchwikilink} >{$batchinfo}</a> 
  </h2>
  <table class="geneform">
    <th>
      Batch ID
    </th>
<!--    <th>
      File Name
    </th> -->
    <th>
      Tags
    </th>
    <th>
      Mol Type
    </th>
    <th>
      PubMed ID
    </th>
    <th>
      Description
    </th>
    {section name=batch loop=$contents}
      <tr>
        <td>
          <a href=view_batch_detail.php?batch_id={$contents[batch].0}>{$contents[batch].0}</a>
        </td>
<!--        <td>
          {$contents[batch].2}
        </td> -->
        <td>
          {$contents[batch].3}
        </td>
        <td>
          {$contents[batch].4}
        </td>
        <td>
          <a href=http://www.ncbi.nlm.nih.gov/pubmed/{$contents[batch].5}>{$contents[batch].5}</a>
        </td>
        <td>
          {$contents[batch].6}
        </td>
      <tr>
    {/section}
  </table>
  <br>
  {if $mol_type === 'learn' and $uploaded >= 1}
  <form name="model" action="model_creation_2.php" method="post">
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Create a classification model from this molecule set!&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;">
  <input type="hidden" name="batch_id" value="{$batch_id}" />  
  </form>
  <br>
  <form name="batch_model" action="view_model.php" method="post">
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;See classification models created from this molecule set!&nbsp;&nbsp;&nbsp;&nbsp;">
  <input type="hidden" name="batch_id" value="{$batch_id}" />  
  </form>
  {elseif $uploaded === '0'}
  <p>Upload process is ongoing...</p>
  {/if}
  {if $who === $username and $uploaded == 2}
      <form name="delete_batch" action="delete_batch.php" method="post">
      <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Delete this Batch&nbsp;&nbsp;&nbsp;&nbsp;">
      <input type="hidden" name="batch_id" value="{$batch_id}" />  
      </form>
  {/if}

  <br />
  <h4>
    {$search}
  </h4>

  <form name="form" action={$phpself} method="get">
  &nbsp;Prediction ID : <input type="text" name="pred_id">
  &nbsp;Model ID : <input type="text" name="model_id">
  <br>
  <input type="hidden" name="batch_id" value="{$batch_id}" />  <br>
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>
  <br>

