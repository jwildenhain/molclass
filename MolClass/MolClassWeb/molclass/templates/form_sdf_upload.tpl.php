
  <h2>
    {$form_data.header.hdrTesting}
  </h2>
  <p>
    {$description}
  </p>
  <p class=alert>
    {$sdf_validation_alert}
  </p>
 <form {$form_data.attributes}>
    <!-- Display the fields -->
    <table class="upload">
      <tr>
        <td>{$form_data.filename.label}</td>
        <td>{$form_data.filename.html}</td>
      </tr>
      <!-- Display the buttons -->
      <tr>
        <td align="left" colspan="0">
          {$form_data.btnSubmit.html}
        </td>
        <td></td>
      </tr>
    </table>
</form>

  
