################################################################################
# 
# R script to show class distributions
#
# 1. read in relevant data
# 2. calculate likelihood score on data
# 3. plot data for Pred_ID's and Model_ID's 
#
################################################################################

#
#
# Functions are here
#
#

# make count histogram with two distributions
superhist2pdf <- function(x, filename = paste(param$R_FILENAME,".pdf",sep=""),
                          dev = "pdf", title = paste("Histogram of",param$R_JOB_TITLE), nbreaks ="Sturges", mylabel) {
  junk = NULL
  grouping = NULL
  for(i in 1:length(x)) {
    junk = c(junk,x[[i]])
    grouping <- c(grouping, rep(i,length(x[[i]]))) }
  grouping <- factor(grouping)
  n.gr <- length(table(grouping))
  xr <- range(junk)
  histL <- tapply(junk, grouping, hist, breaks=nbreaks, plot = FALSE)
  maxC <- max(sapply(lapply(histL, "[[", "counts"), max))
  if(dev == "pdf") { pdf(filename, version = "1.4", width=7, height=6) } else{}
  if((TC <- transparent.cols <- .Device %in% c("pdf", "png"))) {
    cols <- hcl(h = seq(30, by=360 / n.gr, length = n.gr), l = 65, alpha = 0.5) }
  else {
    h.den <- c(10, 15, 20)
    h.ang <- c(45, 15, -30) }
  if(TC) {
      par(bg = param$R_WWW_color_bg)
      plot(histL[[1]], xlim = xr, ylim= c(0, maxC), col = cols[1],border = cols[1], xlab = paste("Likelihood",param$R_JOB_TITLE), main = title) }
  else { plot(histL[[1]], xlim = xr, ylim= c(0, maxC), density = h.den[1], angle = h.ang[1], xlab = "Likelihood") }
  if(!transparent.cols) {
    for(j in 2:n.gr) plot(histL[[j]], add = TRUE, density = h.den[j], angle = h.ang[j]) } else {
      for(j in 2:n.gr) plot(histL[[j]], add = TRUE, col = cols[j], border = cols[j]) }
  invisible()
  #points(INT_RESULT,5,cex = 2,pch=16,col="red");
  xvec <- c()
  yvec <- c()
  for (i in 1:length(x)) {
    tmp <- hist(x[[i]],plot=FALSE)
    tmpit <- which.max(tmp$counts)
    xvec <- append(xvec,tmp$mids[tmpit])
    yvec <- append(yvec,round(tmp$counts[tmpit] *1.05))
  }
  if (xvec[1] < xvec[2]) {
     xvec[1] = xvec[1] - 2
     xvec[2] = xvec[2] + 2
  } else {
    xvec[1] = xvec[1] + 2
    xvec[2] = xvec[2] - 2    
  }
  ym <- mean(yvec)/5
  yvec <- rep(ym,length(yvec))
  text(xvec,yvec,labels=mylabel)

  if( dev == "pdf") {
    dev.off() }
}
#superhist2pdf(l1, nbreaks=50,mylabel=ldata$gpnames)

# get the likelihood numbers
get_likelihood_data <- function(PRED_ID) {
  
  # pull out the 128x128 data and build a model again!
  sql <- paste("SELECT distinct main_class FROM  `prediction_mols` where pred_id in (", PRED_ID,")",sep="")
  rs <- dbSendQuery(GLOBAL_MYCON, sql)
  getClassID <- fetch(rs, n = -1) # get all datapoints
  head(getClassID)
  
  sql <- paste("SELECT * FROM  `prediction_mols`  join sdftags using (mol_id) where pred_id in (", PRED_ID,")",sep="")
  rs <- dbSendQuery(GLOBAL_MYCON, sql)
  given <- fetch(rs, n = -1) # get all datapoints
  head(given)
  dim(given)
  names(given)
  
  # turn string into numbers
  probs <- strsplit(given$distribution, "\t")
  
  classes <- length(probs[[1]])
  # get class probability
  prob <- matrix(0,nrow=length(probs),ncol=length(probs[[1]]))
  
  for (i in 1:length(probs)) {
    for (j in 1:length(probs[[1]])) {
      prob[i,j] <- as.numeric(probs[[i]][j])
    }
  }
  
  lhood <- logit(prob[,2])
  a <- lhood[given$main_class == getClassID$main_class[1]]*-1
  b <- lhood[given$main_class == getClassID$main_class[2]]*-1
  return(list(a=a[!is.na(a)],b=b[!is.na(b)],gpnames = c(getClassID$main_class[1], getClassID$main_class[2])))
}

# probability 2 likelihood function
logit <- function(p,offset=0.001) log((p+offset)/(1+offset-p))


unit_test <- function() {
  
  param <- list(
  R_setbreaks = 100,
  R_DUMP = "./cache/",
  R_UID = "testxyz",
  R_JOB_TITLE = "Unit test",
  R_FILENAME = "unit_test_dist",
  # choose between model or prediction id
  R_P_TYPE = "model",
  # choose between density and count view
  R_P_VIEW = "density",
  # database prediction ID or model ID
  R_P_ID = 1,
  # database connection
  DB_connection_user = "molclass_user",
  DB_connection_host = "localhost",
  DB_connection_database = "molclass",
  DB_connection_password = "YCuRzNoe",
  R_WWW_color_bg = "white" 
  )
  return(param)
}

### Scripts starts here


param <- list(
R_setbreaks = 100,
R_DUMP = Sys.getenv("R_DATA_CACHE"),
R_UID = Sys.getenv("R_UNIQUE_JOB_ID"),
R_JOB_TITLE = Sys.getenv("R_PLOT_TITLE"),
R_FILENAME = Sys.getenv("R_FILENAME"),
R_SHOW_PLOT = Sys.getenv("R_SHOW_PLOT"),
# choose between model or prediction id
R_P_TYPE = Sys.getenv("R_P_TYPE"),
# choose between density and count view
R_P_VIEW = Sys.getenv("R_P_VIEW"),
# database connection
R_P_ID = Sys.getenv("R_P_ID"),
# database connection
DB_connection_user = Sys.getenv("R_DB_CONST_USER"),
DB_connection_database = Sys.getenv("R_DB_CONST_DATABASE"),
DB_connection_host = Sys.getenv("R_DB_CONST_HOST"),
DB_connection_password = Sys.getenv("R_DB_CONST_PWD"),
R_WWW_color_bg = Sys.getenv("R_WWW_color_bg")  
)
#param

# if no sysgetenv parameters available run unit_test
if (regexpr(param$DB_connection_user, param$R_UID) == TRUE) {
  param <- unit_test()
}
param

param$R_FILENAME = paste(param$R_DUMP,param$R_FILENAME,sep="")

# load libraries
library(DBI)
library(RMySQL)

GLOBAL_MYCON <- dbConnect(MySQL(), user=param$DB_connection_user, dbname=param$DB_connection_database, host=param$DB_connection_host, password=param$DB_connection_password)

# get all predictions for one Model across all batches.
# deal with whole model dataset (all its predictions on all batches)
if (param$R_P_TYPE == "Model") {
  sql <- paste("SELECT distinct pred_id FROM  `prediction_list` where model_id =", param$R_P_ID,sep="")
  rs <- dbSendQuery(GLOBAL_MYCON, sql)
  getAllPredictions <- fetch(rs, n = -1) # get all datapoints
  getAllPredictions
  PRED_ID <- toString(getAllPredictions)
  PRED_ID
  #PRED_ID <- as.character(paste(getAllPredictions, collapse=","))
  #PRED_ID <- gsub("c", "",PRED_ID)
  #PRED_ID <- gsub(")", "",PRED_ID)
  #PRED_ID <- substring(PRED_ID,2)
  PRED_ID <- gsub(":",",",PRED_ID)
  head(PRED_ID)
}

# deal with single prediction datasets on a single batch
if (param$R_P_TYPE == "Prediction") {
  # single dataset
  PRED_ID <- param$R_P_ID 
}
# do calculus
ldata <- get_likelihood_data(PRED_ID)

###############################
# show as nice density graph  #
###############################
if (param$R_P_VIEW == "density") { 
  library(DAAG)
  pdf(paste(param$R_FILENAME,".pdf",sep=""), width=7, height=6, version = "1.4",title = "Likelihood Density between two classes")
  overlapDensity(ldata$a,ldata$b,ratio=c(0.5,1),gpnames = ldata$gpnames,xlab=paste("Likelihood",param$R_JOB_TITLE))  
  #dev.print(pdf, width=7, height=6, file=paste(param$R_FILENAME,".pdf",sep=""), title = "Likelihood Density between two classes")
  dev.off()
}
###############################
# show as total count graph   #
###############################
if (param$R_P_VIEW == "count") {
           l1 = list(ldata$a,ldata$b)
         	 superhist2pdf(l1, nbreaks=50,mylabel=ldata$gpnames)
}
