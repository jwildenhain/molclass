<?php
  
        if(!isset($_GET['llh'])) {
                  $_GET['llh'] = 0;
        }
        $llh = $_GET['llh'];

        header("Content-type: image/jpeg");
        // read the post data
        //$data = array('3400','2570','245','473','1000','3456','780');
        //$sum = array_sum($data);
        
        $maxbar = 60;
        $barunit = $maxbar/14;

        $im = imagecreatetruecolor($maxbar, 10);
        $red = imagecolorallocate($im, 250, 0, 0);
        $green = imagecolorallocate($im, 0, 250, 0);
        $bkgcolor = imagecolorallocate($im, 245, 245, 245);

        #
        $middle = $maxbar/2;
        $scoreshift = $middle + ($llh*$barunit);     

        // Draw a white rectangle
        imagefilledrectangle($im, 0, 0, $maxbar, 10, $bkgcolor);

        if ($scoreshift >= $middle) {
             imagefilledrectangle($im, $middle, 0, $scoreshift, 10, $green);
        } else {
             imagefilledrectangle($im, $scoreshift, 0, $middle, 10, $red);
        }
        imagejpeg($im);

?>
