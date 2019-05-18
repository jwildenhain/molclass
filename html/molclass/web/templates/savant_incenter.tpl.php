
  <table class="incenter">
    <tr><td>
  <div id="centercontent">
	
	
	<p>
	<?php 
                       $filename = $this->plate_image.$this->plate_id."/".$this->plate_id."_".$this->plate_row.$this->plate_col.".png";
                       //print_r($filename);
	               if (file_exists($filename)) { 
	                 	echo "<a href="."display.php?large_img=".$filename. "> <img class=\"imageleft\" border=\"0\" src=\"".$filename."\" width=\"300\" height=\"220\" alt=\"testing image link\" > </a>";
	               } else { echo "<img class=\"imageleft\" border=\"0\" src=\"images/opera_no_picture.png\" width=\"300\" height=\"220\" >"; } 
	?>
	<h1> <?php echo ($this->gene_symbol) ?> (<?php  if (isset($this->title)) { echo ($this->title); } else { echo "NA"; }  ?>) </h1>
	Lookup in: 
	      <?php if (isset($this->ensp_id)) { echo ("<a href=".$this->www_string.$this->ensp_id.">String</a>"); } ?>
	      <?php if (isset($this->gene_symbol)) { echo ("<a href=".$this->www_ihop.$this->gene_symbol.">Ihop</a>"); } ?>
	      <?php if (isset($this->gene_symbol)) { echo ("<a href=".$this->www_genecards.$this->gene_symbol.">Genecards</a>"); } ?>
	      <?php if (isset($this->gene_symbol)) { echo ("<a href=".$this->www_biogrid.$this->gene_symbol.">BioGrid</a>"); } ?>
	<br>      
<!--	Container: <?php if (isset($this->container_number)) { echo ($this->container_number); } else { echo "NA"; }   ?> Well: <?php  echo ($this->well)  ?> <br> -->
<!--	Catalog: <?php if (isset($this->catalog_number)) { echo ($this->catalog_number); } else { echo "NA"; }   ?> <br> -->
	Gene Status: <?php if (isset($this->genestatus)) { echo ($this->genestatus); } else { echo "NA"; }   ?> <br>
	Category: <?php if (isset($this->category)) { echo ($this->category); } else { echo "NA"; }   ?> <br> 
	Locus Id (NCBI): <?php if (isset($this->locus_id)) { echo ("<a href=".$this->www_entrez.$this->locus_id.">".$this->locus_id."</a>"); } else { echo "NA"; } ?> <br> 
	Accession: <?php if (isset($this->accession)) { echo ($this->accession); } else { echo "NA"; } ?><br> 
	cDNA entry source: <?php if (isset($this->seq_entry_source_cdna)) { echo ("<a href=".$this->www_nucseq.$this->seq_entry_source_cdna.">".$this->seq_entry_source_cdna."</a>"); } else { echo "NA"; } ?><br> 
	Protein entry source: <?php   if (isset($this->seq_entry_source_protein)) { echo ($this->seq_entry_source_protein); } else { echo "NA"; }   ?> <br> 
	Ensemble ID: <?php if (isset($this->ensp_id)) { echo ("<a href=".$this->www_ensembl.$this->ensp_id.">".$this->ensp_id."</a>"); } else { echo "NA"; } ?> <br>
	HPRD ID: <?php 
	               if (isset($this->hprd_id)) { 
	                 	$link = str_replace("#UID#",substr($this->hprd_id,5),$this->www_hprd);
	                        echo ("<a href=".$link.substr($this->hprd_id,5).">".substr($this->hprd_id,5)."</a>"); 
	               } else { echo "NA"; } 
	         ?> <br>
	Omim ID: <?php if (isset($this->omim)) { echo ("<a href=".$this->www_omim.$this->omim.">".$this->omim."</a>"); } else { echo "NA"; } ?> <br>
	</p>
	<p>
        Cellular Component: <?php if (isset($this->cc_title)) echo ($this->cc_title) ?>  <br>
        Molecular Function: <?php if (isset($this->mf_title)) echo ($this->mf_title) ?>  <br> 
        Bioglogical Process:  <?php if (isset($this->bp_title)) echo ($this->bp_title) ?> <br>	
        Description: <?php if (isset($this->description)) echo ($this->description) ?>  <br> 
        </p>
	 	

	<br>
	<a href =display.php?base=dapi&genelist=<?php  echo ($this->gene_symbol)  ?>&breaks=250&PNG&library=<?php  echo ($this->library) ?>&p=<?php  echo ($this->plate_id) ?>>[show dapi]</a> 	
	<a href =display.php?base=pca&PNG&library=<?php  echo ($this->library) ?>&p=<?php  echo ($this->plate_id) ?>&gene=<?php  echo ($this->gene_symbol) ?>>[pca plate summary]</a> 
	<a href =display.php?base=inplatecheck&library=<?php  echo ($this->library) ?>&select=TotalSpotAreaMean&p=<?php  echo ($this->plate_id) ?>&PNG >[in plate replicate]</a> 
	<a href =display.php?p=<?php  echo ($this->plate_id) ?>&row=<?php  echo ($this->plate_row) ?>&col=<?php  echo ($this->plate_col) ?>&PNG&library=<?php  echo ($this->library) ?>&base=wellsummary >[well summary]</a> 
	<a href =display.php?base=heatmap&library=<?php  echo ($this->library) ?>&pinning=&select=TotalSpotAreaMean&p=<?php  echo ($this->plate_id) ?>&PNG >[plate heatmap]</a> 
	<?php  if (!isset($this->b1))  echo "<!--" ?>	<a href =display.php?base=platepaircheck&library=<?php  echo ($this->library) ?>&select=TotalSpotAreaMean&p1=<?php  echo ($this->b1) ?>&p2=<?php  echo ($this->b2) ?>&PNG >[between plates]</a> <?php  if (!isset($this->b1))  echo "-->" ?>
	<?php  if (!isset($this->b1))  echo "<!--" ?> <a href =display.php?library=<?php  echo ($this->library) ?>&base=scatterZ&p1=<?php  echo ($this->b1) ?>&p2=<?php  echo ($this->b2) ?>&PNG >[plate Z-adjust]</a> <?php  if (!isset($this->b1))  echo "-->" ?>
	<?php  if (!file_exists($filename)) echo "<!--" ?> <a href =<?php echo str_replace("rnai_info","image_compare",($this->images_url)) ?> >[compare images]</a> <?php  if (!file_exists($filename))  echo "-->" ?>
</div>

 <div id="centercontent">
 Z-adjust pair: <?php   if (isset($this->z_adjust_pair_value)) { echo round($this->z_adjust_pair_value,3); } else { echo "NA"; } ?>
 Z-adjust single: <?php   if (isset($this->z_adjust_single_value)) { echo ($this->z_adjust_single_value); } else { echo "NA"; }   ?> <br> 
 Neighbourhood phenotype -<?php   if (isset($this->MAD_median)) { echo " Outliers: ".$this->MAD_hits." Median Distance: ".$this->MAD_median." MAD deviation: ". $this->MAD_dev; } ?> <br> 
 G1 proportion: <br>
 G2 proportion: <br>
 Next <?php echo $this->z_adjust_down_limit." Z-adjusts down: "; foreach ($this->z_adjust_down as $key => $val) { echo $val; } ?> <br>
 Next <?php echo $this->z_adjust_up_limit." Z-adjusts up: "; foreach ($this->z_adjust_up as $key => $val) { echo $val;  } ?> <br> 
 <?php 
 if (isset($this->external_data)) { foreach ($this->external_data as $key => $val) { echo "External source: ".("<a href=rnai_table.php?library=".$key."&symbol=".$this->gene_symbol.">".$key."</a>")." Score: ".$val."<br>"; }  } else { echo "NA"; }  
 ?>
 <br>

 Plate ID/Barcode: <?php   if (isset($this->b1)) { echo ($this->b1); } else { echo "NA"; }   ?> and <?php   if (isset($this->b2)) { echo ($this->b2); } else { echo "NA"; } ?><br> 
 Commment on plates: <?php   if (isset($this->information)) { echo ($this->information); } else { echo "NA"; }   ?> <br> 
 Experiment done by: <?php   if (isset($this->owner)) { echo ($this->owner); } else { echo "NA"; }   ?> <br> 
 Date of experiment: <?php   if (isset($this->experimentdate)) { echo ($this->experimentdate); } else { echo "NA"; }   ?> <br>
 Replicability - in plates: <?php  echo ($this->b1_a2c + $this->b1_b2d)/2 ." ". ($this->b2_a2c + $this->b2_b2d)/2; ?>  between plates: <?php $ar = array( $this->b1_2_b2_a2a,$this->b1_2_b2_a2b,$this->b1_2_b2_a2c,$this->b1_2_b2_b2b,$this->b1_2_b2_c2c,$this->b1_2_b2_d2d); echo round(array_sum($ar)/count($ar),3)."&plusmn;". round(stats_standard_deviation($ar),3);   ?>  <br> 
 
 Background - in plates: <?php  $ar = array($this->b1_a2b,$this->b1_a2d,$this->b1_b2c, $this->b1_c2d); echo round(array_sum($ar)/count($ar),3)."&plusmn;". round(stats_standard_deviation($ar),3); echo " ";  $ar = array($this->b2_a2b,$this->b2_a2d,$this->b2_b2c, $this->b2_c2d); echo round(array_sum($ar)/count($ar),3)."&plusmn;". round(stats_standard_deviation($ar),3); ?> 
 between plates: <?php $ar = array( $this->b1_2_b2_b2c , $this->b1_2_b2_b2d ,$this->b1_2_b2_a2d,$this->b1_2_b2_c2d); echo round(array_sum($ar)/count($ar),3)."&plusmn;". round(stats_standard_deviation($ar),3);    ?>  <br> 

 <br>

 </div>
 <div id="centercontent">
 Interaction partners: <br>
 <?php foreach ($this->interaction_partners as $key => $val) { echo $val;  } ?> <br> 

 </div>
 <div id="centercontent">
 Neighbourhood phenotypes: <br>
 <?php   if (isset($this->pca_vector)) { foreach ($this->pca_vector as $key => $val) { echo $val; }  } else { echo "NA"; }   ?> <br> 

 </div>





   </td> </tr>
  </table> 
  
