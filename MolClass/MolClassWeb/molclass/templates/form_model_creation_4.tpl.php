
  <h2>
    {$header}
  </h2>
  {if $exist eq 'exist'}
    <p>
      {$description}
    </p>
    <a href=view_model_detail.php?model_id={$model_id}>Click here to see the prediction model</a>
  {else}
    <p>
      {$description}
    </p>
  {/if}

  
