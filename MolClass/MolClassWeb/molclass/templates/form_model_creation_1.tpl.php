
  <h2>
    {$title}
  </h2>
  
  <h4>
    {$search}
  </h4>

  <form name="form" action={$phpself} method="get">
  Batch ID : <input type="text" name="batch_id" maxlength="8" style="width: 150px;">
  &nbsp;&nbsp;&nbsp;User : <select name="who">
           <option value=""></option>
           <option value="you">You</option>
           <option value="other">Other Users</option>
         </select>
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>
  <br />
  
  <!-- http://www.cssbuttongenerator.com/ -->

