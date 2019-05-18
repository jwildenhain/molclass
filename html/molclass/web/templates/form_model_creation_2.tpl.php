
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
    <th>Data Type and short info</th>
    <tr>
      <td>Random Forest</td>
      <td>any datatype, currently no information about incluence of single variables</td>
    </tr>
    <tr>
      <td>Logistic Model Tree (LMT)</td>
      <td>previously CDK only, it seems to work with all datatypes since upgrade</td>
    </tr>
    <tr>
      <td>J48</td>
      <td>any datatype, does range scan, char tree to show properties of influence</td>
    </tr>
    <tr>
      <td>LogitBoost</td>
      <td>any data type, Logistic Regression method with property information</td>
    </tr>
     <tr>
      <td>RacedIncrementalLogitBoost</td>
      <td>only use with very large datasets, otherwise strong tendency to over-learning</td>
    </tr>
    <tr>
      <td>NBTree</td>
      <td>any data type, Hall M. et al. 2007</td>
    </tr>

    <tr>
      <td>DecisionTreeNaiveBayes</td>
      <td>any data type, Ratanamahatana C.A. et al. 2002 </td>
    </tr>  
    <tr>
      <td>Naive Bayes</td>
      <td>any datatype, with weight kernels, does display impact of properties</td>
    </tr>
    <tr>
      <td>HiddenNaiveBayes</td>
      <td>nominal only - will fail with CDK, ALL, JUMBO and MCAT, Liangxiao J. et al. 2009 </td>
    </tr>
    <tr>
      <td>BayesNet</td>
      <td>any data type, local search algorithm</td>
    </tr>
    <tr>
      <td>NeuralNet</td>
      <td>any data type, Multilayerperceptron</td>
    </tr>
    <tr>
      <td>k-Nearest Neighbor (KNN)</td>
      <td>any datatype, currently scans range from 3 to 20 neighbors</td>
    </tr>
    <tr>
      <td>Sequential Minimal Optimization (SMO)</td>
      <td>any datatype, trade-off error/complexity scan between 0 and 10 </td>
    </tr>
    <tr>
      <td>Support Vector Machines (LIBSVM)</td>
      <td>any combination, C-SVC with radial basis function</td>
    </tr>
    <!-- <tr>
      <td>Support Vector Machines (LIBSVM2)</td>
      <td>any combination</td>
    </tr> -->    
    <tr>
      <td>Ensemble</td>
      <td>any data type, vote on J48, Naive Bayes, OneR, DecisionStump, RF, SMO </td>
    </tr>
        <tr>
      <td>Ensemble2</td>
      <td>any data type, LR on J48, LMT, NeuralNet, OneR, SMO poly, IBk, Nearest Neighbor </td>
    </tr>
    
  </table>

  
