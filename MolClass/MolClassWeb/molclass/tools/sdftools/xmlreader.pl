#!/usr/bin/perl

use XML::Simple;
$xml = new XML::Simple;

#
# adjust the following lines to your server settings
#

$data = $xml->XMLin("molclass.conf.xml");

#
# Please do not make changes below if you are not sure what you need to do
#

$database      = $data->{database};
$hostname      = $data->{hostname};
$rw_user       = $data->{rw_user};
$rw_password   = $data->{rw_password};
$ro_user       = $data->{ro_user};
$ro_password   = $data->{ro_password};     
$molstructable = $data->{molstructable};
$moldatatable  = $data->{moldatatable};
$molstattable  = $data->{molstattable};
$molfgtable    = $data->{molfgtable};
$molfgbtable   = $data->{molfgbtable};
$molbfptable   = $data->{molbfptable};
$molcfptable   = $data->{molcfptable}; 
$fpdeftable    = $data->{fpdeftable};
$molhfptable   = $data->{molhfptable};
$metatable     = $data->{metatable};
$pic2dtable    = $data->{pic2dtable};
$scratchdir    = $data->{scratchdir};
$strucinfotable =$data->{strucinfotable};
$plateinfotable =$data->{plateinfotable};
$cdkdesctable = $data->{cdkdesctable};
$batchlisttable = $data->{batchlisttable};
$batchmoltable = $data->{batchmoltable};
$fingerprinttable = $data->{fingerprinttable};
$modeltable = $data->{modeltable};
$classschemetable = $data->{classschemetable};
$predtable = $data->{predtable};
$datatypetable = $data->{datatypetable};
$predmoltable = $data->{predmoltable};
$timeouttable = $data->{timeouttable};
$inchikeytable = $data->{inchikeytable};
$smilestable = $data->{smilestable};
$moltypetable = $data->{moltypetable};
$tempbatchmoltesttable = $data->{tempbatchmoltesttable};
$tempbatchmollearntable = $data->{tempbatchmollearntable};

$bitmapURLdir  = $data->{bitmapURLdir};
$digits        = $data->{digits};
$subdirdigits  = $data->{subdirdigits};
$sitename      = $data->{sitename};
$CHECKMOL      = $data->{CHECKMOL};
$MATCHMOL      = $data->{MATCHMOL};
$MOL2SVG       = $data->{MOL2SVG};
$MOL2PS        = $data->{MOL2PS};
$GHOSTSCRIPT   = $data->{GHOSTSCRIPT};

$toolsdir 		= $data->{toolsdir};
$model_pred_dir		= $data->{model_pred_dir};
$web_server_location	= $data->{website};

$setHeapSize = $data->{setHeapCacheSize};




