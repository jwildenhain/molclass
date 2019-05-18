
  <h2>
    {$title}
  </h2>
  
  <h4>
    {$search}
  </h4>

  <form name="form" action={$phpself} method="get">
  Model ID : <input type="text" name="model_id">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  Batch ID : <input type="text" name="batch_id">
  <br/>
  <br/>
  User : <select name="who">
           <option value=""></option>
           <option value="you">You</option>
           <option value="other">Other Users</option>
         </select>&nbsp;&nbsp;&nbsp;&nbsp;
  Data Type : <select name="data_type">
                <option value=""></option>
                {foreach from=$data_type item=data}
                  <option value="{$data}">{$data}</option>
                {/foreach}
              </select>&nbsp;&nbsp;&nbsp;&nbsp;
  Model : <select name="class_scheme">
                <option value=""></option>
                {foreach from=$class_model item=model}
                  <option value="{$model}">{$model}</option>
                {/foreach}
              </select>
  <br />
  <br />
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>
  

