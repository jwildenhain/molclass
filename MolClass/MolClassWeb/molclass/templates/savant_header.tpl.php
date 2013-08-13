<?php if (is_array($this->mainheader)): ?>


  <table class="header">
    <tr>
       <th> <img src="<?php  echo ($this->maintableimage)  ?>" width="670" height="61"></th>
    </tr>
    <tr>
       <td >
           
           <?php foreach ($this->mainheader as $key => $val): ?>
               | <a href="http://<?php  echo $this->url .  $val['link']    ?>"><?php    echo $val['name']   ?></a> 
           <?php endforeach; ?>
       </td>
    </tr>
  </table>    
     
<?php else: ?>
            
            <p> No header display.</p>
            
<?php endif; ?>
        
