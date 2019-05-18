
  <h2>
    {$form_data.header.hdrTesting}
  </h2>
 <form {$form_data.attributes}>
    <!-- Display the fields -->

    <table class="geneform">
      <p class=alert>{$sdftag_alert}</p>
      <p class=alert>{$sdftag_same_column}</p>
      {section name=sdftag loop=$sdftag}
      <tr>
        <td>{$form_data.{$sdftag[sdftag]}.label}</td>
        <td>{$form_data.{$sdftag[sdftag]}.html}</td>
      </tr>
      {/section}
      <tr>
        <td>{$form_data.INT_mol_name.label}</td>
        <td>{$form_data.INT_mol_name.html}</td>
      </tr>
      <tr>
        <td>{$form_data.molclass_mol_type.label}</td>
        <td>{$form_data.molclass_mol_type.html}</td>
      </tr>
      <tr>
        <td>{$form_data.molclass_pmid.label}</td>
        <td>{$form_data.molclass_pmid.html}</td>
      </tr>
      <tr>
        <td>{$form_data.molclass_info.label}</td>
        <td>{$form_data.molclass_info.html}</td>
      </tr>
      <tr>
        <td>{$form_data.molclass_email.label}</td>
        <td>{$form_data.molclass_email.html}</td>
      </tr>
      <input type="hidden" name="sdfid" value="{$sdfid}" />
    </table>
    <!-- Display the buttons -->
    <div align=center>{$form_data.btnSubmit.html}</div>
</form>
<h3>
  Current columns in structure info table:

</h3>
<table class="geneform">
  <th>
    Name
  </th>
  <th>
    Column Type
  </th>
  {section name=column loop=$contents}
  <tr>
    <td>
      {$contents[column].0}
    </td>
    <td>
      {$contents[column].1}
    </td>
  <tr>
  {/section}
</table>


  
