#!/usr/bin/env python
# encoding: utf-8
"""
untitled.py

Created by Florian Nigsch on 2007-09-27.
Copyright (c) 2007. All rights reserved.
"""

import sys
import getopt
import re
import os

help_message = '''
Add one field to each molecule in an SDF file.
Flags:
	-h  Display this help message
	-v  Verbose output
Arguments:
	-i <file>    Name of input file
	-o <file>    Name of output file
	-l <file>    Name of file to read tags from;
	             if not provided, then molecule will be
	             numbered sequentially starting from 0.
	-n <string>  Name of the tag to be added, defaults to PYTAG

Format of file for tags:
For each molecule a line with colon separated values that should be appended
under the given name of the SDF tag.'''


class Usage(Exception):
	def __init__(self, msg):
		self.msg = msg

def usage():
	print help_message

def main(argv=None):
	labelfile = ""
	SDFtagname = "PYTAG"
	inputfile = ""
	outputfile = ""
	if argv is None:
		argv = sys.argv
	try:
		try:
			opts, args = getopt.getopt(argv[1:], "ho:vi:l:n:", ["help", "output="])
		except getopt.error, msg:
			raise Usage(msg)
	
		# option processing
		for option, value in opts:
			if option == "-v":
				verbose = True
			if option in ("-h", "--help"):
				raise Usage(help_message)
			if option in ("-o", "--output"):
				outputfile = value
			if option in ("-i", "--input"):
				inputfile = value
			if option in ("-l", "--label"):
				labelfile = value
			if option in ("-n", "--tagname"):
				SDFtagname = value
		
	
	except Usage, err:
		print >> sys.stderr, sys.argv[0].split("/")[-1] + ": " + str(err.msg)
		print >> sys.stderr, "\t for help use --help"
		return 2
	
	try:
		if inputfile == "":
			raise Exception("No input file specified.")
		if not os.path.isfile(inputfile):
			raise Exception("Input file does not exist.")
	except Exception, e:
		print e
		usage()
		sys.exit(1)
	
	try:
		if labelfile == "":
			print "Continuously numbering molecules."
			pass
		else:
			if not os.path.isfile(labelfile):
				raise Exception("Labels file does not exist.")
	except Exception, e:
		print e
		usage()
		sys.exit(1)
	try:
		if outputfile == "":
			raise Exception("No outputfile specfied!")
	except Exception, e:
		print e
		usage()
		sys.exit(1)
	
	out = open(outputfile,'w')
	if labelfile != "":
		labels = open(labelfile,'r')
		sys.stdout.write("Reading labels from: %s\n" % labelfile)
		sys.stdout.flush()
	
	sys.stdout.write('Tagname: %s\n' % SDFtagname)
	sys.stdout.write('Each dot corresponds to 100 molecules: ')
	sys.stdout.flush()
	labelnum = 1
	MolCount = 0
	for line in open(inputfile,'r'):
		if re.search(r'\$\$\$\$', line):
			MolCount += 1
			if not MolCount % 100:
				sys.stdout.write('.')
				sys.stdout.flush()
			out.write("> <%s>\n" % SDFtagname)
			if labelfile != "":
				label = labels.readline()
				label = label[:-1].split(';')
				tmp = label
				label = ""
				for l in tmp:
					label += "%s\n" % l
			else:
				label = str(labelnum)+'\n'
				labelnum += 1
			out.write("%s\n" % label)
			out.write(line)
		else:
			out.write(line)
	out.close()
	if labelfile != "":
		labels.close()
	sys.stdout.write('\nAdded tags to %d molecules.\n' % MolCount)

if __name__ == "__main__":
	sys.exit(main())