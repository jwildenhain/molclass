
  <h2>
    {$form_data.header.hdrTesting}
  </h2>
 <form {$form_data.attributes}>
    <!-- Display the fields -->

    <table class="geneform">
               <tr>
                    <td >{$form_data.r_title.label}</td>
                    <td>{$form_data.r_title.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_fn.label}</td>
                    <td>{$form_data.r_fn.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_ln.label}</td>
                    <td>{$form_data.r_ln.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_email.label}</td>
                    <td>{$form_data.r_email.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_as.label}</td>
                    <td>{$form_data.r_as.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_loc.label}</td>
                    <td>{$form_data.r_loc.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.r_pw.label} </td> 
                    <td>{$form_data.r_pw.html}</td>
               </tr>    
               <tr>
                    <td>{$form_data.r_pw_conf.label}</td>
                    <td>{$form_data.r_pw_conf.html}</td>
               </tr>
      <!-- Display the buttons -->
      <tr>
        <td>A password will be generated,<br> when not provided.</td>
        <td align="center" colspan="0">
          {$form_data.btnSubmit.html}
        </td>
      </tr>
    </table>
  </form>


  
