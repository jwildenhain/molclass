
  <h2>
    {$form_data.header.hdrTesting}
  </h2>
  <p>
    {$description}   
  </p>
 <form {$form_data.attributes}>
    <!-- Display the fields -->

    <table class="geneform">
               <tr>
                    <td>Batch ID</td>
                    <td>{$batch_id}</td>
               </tr>
               <tr>
                    <td>Data Type</td>
                    <td>{$data_type}</td>
               </tr>
               <tr>
                    <td>Class Scheme</td>
                    <td>{$class_schemes}</td>
               </tr>
               <tr>
                    <td>Classifier</td>
                    <td>{$classifier}</td>
               </tr>
               <tr>
                    <td>Email</td>
                    <td>{$email}</td>
               </tr>
               <input name="batch_id" type="hidden" value="{$batch_id}"/>
               <input name="data_type" type="hidden" value="{$data_type}"/>
               <input name="class_schemes" type="hidden" value="{$class_schemes}"/>
               <input name="classifier" type="hidden" value="{$classifier}"/>
               <input name="email" type="hidden" value="{$email}"/>
      <!-- Display the buttons -->
      <tr>
        <td></td>
        <td align="center" colspan="0">
          {$form_data.btnSubmit.html}
        </td>
      </tr>
    </table>
  </form>


  
