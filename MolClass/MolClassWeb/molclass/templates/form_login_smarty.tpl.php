
<span id="loginForm">
  <form {$loginForm_data.attributes}>
    <!-- Display the fields -->
    <table width="600">
      <tr>
        <th class="form_login_label">{$loginForm_data.username.label}</th>
        <th class="form_login_label">{$loginForm_data.password.label}</th>
        <th>&nbsp;</th> 
      </tr>
      <tr>
        <td>{$loginForm_data.username.html}</td>
        <td>{$loginForm_data.password.html}</td>
        <td>{$loginForm_data.btn_login.html}</td>
      </tr>
      <tr>
        <td style="font-size: 0.8em; width: 250px; color: red;">{if $login_error}{$login_error}{/if}</td>
        <td></td>
        <td></td>
      </tr>
    </table>
  </form>
  <p>
    Dear user, <br> as datasets are assigned to specific users we require you to create yourself an account in MolClass. Please use the 'Create MolClass Account' form provided.
    An automatic email will be sent after submission. Usually it takes less then two minutes to finish the registration process.
    <br><br><b><a href=user.php>{$newuser}</a></b>
  </p>
</span>


