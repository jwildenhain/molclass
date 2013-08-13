
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
                    <td>{$form_data.data_type.label}</td>
                    <td>{$form_data.data_type.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.class_schemes.label}</td>
                    <td>{$form_data.class_schemes.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.classifier.label}</td>
                    <td>{$form_data.classifier.html}</td>
               </tr>
               <tr>
                    <td>{$form_data.email.label}</td>
                    <td>{$form_data.email.html}</td>
               </tr>
               <input name="batch_id" type="hidden" value="{$batch_id}"/>
      <!-- Display the buttons -->
      <tr>
        <td></td>
        <td align="center" colspan="0">
          {$form_data.btnSubmit.html}
        </td>
      </tr>
    </table>
  </form>
  <br/>
  <p class=alert>You have to choose attributes and classifier according to table below.<br/>Otherwise, the classification model is very likely to fail.</p>
  <table class="geneform">
    <th>Classification Scheme</th>
    <th>Attributes</th>
    <tr>
      <td>Random Forest</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>Logistic Model Tree (LMT)</td>
      <td>CDK only</td>
    </tr>
    <tr>
      <td>J48</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>Naive Bayes</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>k-Nearest Neighbor (KNN)</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>Sequential Minimal Optimization (SMO)</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>Support Vector Machines (LIBSVM)</td>
      <td>any combination</td>
    </tr>
    <tr>
      <td>...</td>
      <td></td>
    </tr>
    <tr>
      <td>all others are still experimental</td>
      <td>documentation will follow...</td>
    </tr>
    
  </table>

  
