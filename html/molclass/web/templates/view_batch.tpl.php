
  <h2>
    {$title}
  </h2>
  
  <h4>
    {$search}
  </h4>

  <form name="form" action={$phpself} method="get">
  Batch ID : <input type="text" name="batch_id">&nbsp;&nbsp;&nbsp;&nbsp;
  User : <select name="who">
           <option value=""></option>
           <option value="you">You</option>
           <option value="other">Other Users</option>
         </select>&nbsp;&nbsp;&nbsp;&nbsp;
  Molecule Type : <select name="mol_type">
                <option value=""></option>
                {foreach from=$molecule_type item=data}
                  <option value="{$data}">{$data}</option>
                {/foreach}
              </select>
  <br />
  <br />
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Search&nbsp;&nbsp;&nbsp;&nbsp;">
  </form>

