
  <h2>
    {$title}
  </h2>
  
  <h4>
    {$delete}
  </h4>

  <table class="geneform">
    <th>
      Batch ID
    </th>
    <th>
      File Name
    </th>
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
          {$contents[batch].0}
        </td>
        <td>
          {$contents[batch].2}
        </td>
        <td>
          {$contents[batch].3}
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
      <tr>
    {/section}
  </table>
  <br /> 
  <form name="form" action={$to} method="post">
    <input type="hidden" name="batch_id" value="{$batch_id}" />
    <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Confirm&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>


