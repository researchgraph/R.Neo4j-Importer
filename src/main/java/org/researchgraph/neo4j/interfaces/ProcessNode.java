package org.researchgraph.neo4j.interfaces;

import org.neo4j.graphdb.Node;

public interface ProcessNode {
	boolean processNode(Node node) throws Exception;
}
