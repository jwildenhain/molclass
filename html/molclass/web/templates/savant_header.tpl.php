<?php if (is_array($this->mainheader)): ?>

<script>
  (function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');

  ga('create', 'UA-53024048-1', 'auto');
  ga('send', 'pageview');

</script>
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
        
