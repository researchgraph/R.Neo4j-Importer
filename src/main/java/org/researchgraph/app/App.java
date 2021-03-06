package org.researchgraph.app;
	   
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;


import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.researchgraph.configuration.Properties;
import org.researchgraph.crosswalk.CrosswalkRG;
import org.researchgraph.graph.Graph;
import org.researchgraph.neo4j.Neo4jDatabase;

import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

public class App {

    private static CrosswalkRG crosswalkRG;
    private static Neo4jDatabase neo4j;
    private static Boolean verbose;
    private static Boolean profilingEnabled;

	public static void main(String[] args) {
		try {

		    //Program arguments
			Configuration properties = Properties.fromArgs(args);
	        String bucket = properties.getString(Properties.PROPERTY_S3_BUCKET);
	        String prefix = properties.getString(Properties.PROPERTY_S3_PREIFX);
	        String xmlFolder = properties.getString(Properties.PROPERTY_XML_FOLDER);
	        String xmlType = properties.getString(Properties.PROPERTY_XML_TYPE);
	        String source = properties.getString(Properties.PROPERTY_SOURCE);
	        String crosswalk = properties.getString(Properties.PROPERTY_CROSSWALK);
            String versionFolder = properties.getString(Properties.PROPERTY_VERSIONS_FOLDER);
			verbose = Boolean.parseBoolean( properties.getString(Properties.PROPERTY_VERBOSE));
			profilingEnabled=Boolean.parseBoolean(properties.getString(Properties.PROPERTY_PROFILING));

            System.out.println("Verbose: " +  verbose.toString());
            System.out.println("Profiling enabled: " +  profilingEnabled.toString());

            if (StringUtils.isEmpty(versionFolder))
                System.out.println("Version folder: " + versionFolder);


            //Set Neo4j connection
            String neo4jFolder = properties.getString(Properties.PROPERTY_NEO4J_FOLDER);
            if (StringUtils.isEmpty(neo4jFolder))
                throw new IllegalArgumentException("Neo4j Folder can not be empty");
            System.out.println("Neo4J: " + neo4jFolder);
            neo4j = new Neo4jDatabase(neo4jFolder);
            neo4j.setVerbose(verbose);


            //Set Crosswalk settings
            CrosswalkRG.XmlType type = CrosswalkRG.XmlType.valueOf(xmlType);
            crosswalkRG = new CrosswalkRG();
            crosswalkRG.setSource(source);
            crosswalkRG.setType(type);
            crosswalkRG.setVerbose(verbose);

            //Set XSLT template
            Templates template = null;
	        if (!StringUtils.isEmpty(crosswalk)) {
                System.out.println("XSLT Crosswalk: " + crosswalk);
				TransformerFactory transformerFactory = net.sf.saxon.TransformerFactoryImpl.newInstance();
				template = transformerFactory.newTemplates(new StreamSource(crosswalk));
	        }


            if (!StringUtils.isEmpty(bucket) && !StringUtils.isEmpty(prefix)) {
	        	System.out.println("S3 Bucket: " + bucket);
	        	System.out.println("S3 Prefix: " + prefix);
	        	processS3Objects(bucket, prefix, template, versionFolder,source, verbose);

	        } else if (!StringUtils.isEmpty(xmlFolder)) {
	        	System.out.println("XML: " + xmlFolder);

	        	processFiles(xmlFolder, template);
	        } else
                throw new IllegalArgumentException("Please provide either S3 Bucket and prefix OR a path to a XML Folder");

            if (!StringUtils.isEmpty(crosswalk)) {
                crosswalkRG.printStatistics(System.out);
            }

            neo4j.printStatistics(System.out);

        } catch (Exception e) {
            e.printStackTrace();
            
            System.exit(1);
		}       
	}

	private static void processS3Objects(String bucket, String prefix, Templates template, String versionFolder, String source, Boolean verboseEnabled) throws Exception {

	    AmazonS3 s3client = new AmazonS3Client(new InstanceProfileCredentialsProvider());

    	ListObjectsRequest listObjectsRequest;
		ObjectListing objectListing;
		
		String file = prefix + "/latest.txt";
		S3Object object = s3client.getObject(new GetObjectRequest(bucket, file));
		
		String latest;
		try (InputStream txt = object.getObjectContent()) {
			latest = IOUtils.toString(txt, StandardCharsets.UTF_8).trim();
		}
		
		if (StringUtils.isEmpty(latest)) 
			throw new Exception("Unable to find latest harvest in the S3 Bucket (latest.txt file is empty or not avaliable). Please check if you have access to S3 bucket and did you have completed the harvestring.");	
		
		String folder = prefix + "/" + latest + "/";
		
		System.out.println("S3 Repository: " + latest);
		
	    listObjectsRequest = new ListObjectsRequest()
			.withBucketName(bucket)
			.withPrefix(folder);
	    do {
			objectListing = s3client.listObjects(listObjectsRequest);
			for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {

				file = objectSummary.getKey();
		        System.out.println("Processing file: " + file);
				object = s3client.getObject(new GetObjectRequest(bucket, file));
                InputStream xml = object.getObjectContent();

                processFile(template, xml);
			}
			listObjectsRequest.setMarker(objectListing.getNextMarker());
		} while (objectListing.isTruncated());
	    
	    Files.write(Paths.get(versionFolder, source), latest.getBytes());

		System.out.println(bucket + prefix + " is done.");

	}

	private static void processFiles(String xmlFolder, Templates template) throws Exception {

		File[] files = new File(xmlFolder).listFiles();
		for (File file : files)
			if (file.isDirectory())
            {
                processFiles(file.getAbsolutePath(), template);
            }else {
                if (file.getName().endsWith(".xml") || file.getName().endsWith(".XML")) {
                    try (InputStream xml = new FileInputStream(file)) {
                        System.out.println("Processing file: " + file);
                        processFile(template, xml);
                    }
                }
            }
		System.out.println(xmlFolder + " is done.");
	}

    private static void processFile(Templates template, InputStream xml) throws Exception {
        Graph graph;
        Long markTime = System.currentTimeMillis();
        Long minorMarkTime;
        Long deltaTime;

        if (null != template) {
            Source reader = new StreamSource(xml);
            StringWriter writer = new StringWriter();
            Transformer transformer = template.newTransformer();

            minorMarkTime=System.currentTimeMillis(); //Used for performance profiling
            transformer.transform(reader, new StreamResult(writer));
            if (profilingEnabled) {
                deltaTime = System.currentTimeMillis() - minorMarkTime;
                System.out.println("transform in milliseconds:" + deltaTime);
            }

            InputStream stream = new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8));

            minorMarkTime=System.currentTimeMillis(); //Used for performance profiling
            graph = crosswalkRG.process(stream);
            if (profilingEnabled) {
                deltaTime = System.currentTimeMillis() - minorMarkTime;
                System.out.println("crosswalk.process in milliseconds:" + deltaTime);
            }

        } else {
            minorMarkTime=System.currentTimeMillis(); //Used for performance profiling
            graph = crosswalkRG.process(xml);
            if (profilingEnabled) {
                deltaTime = System.currentTimeMillis() - minorMarkTime;
                System.out.println("crosswalk.process in milliseconds:" + deltaTime);
            }
        }

        minorMarkTime=System.currentTimeMillis(); //Used for performance profiling
        neo4j.importGraph(graph, profilingEnabled);

        if (profilingEnabled) {
            deltaTime = System.currentTimeMillis() - minorMarkTime;
            System.out.println("neo4j.importGraph in milliseconds:" + deltaTime);

            deltaTime = markTime == 0 ? 0 : (System.currentTimeMillis() - markTime);
            System.out.println("completed in milliseconds:" + deltaTime);
        }


    }

}
