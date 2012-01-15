package org.yaoha;

import java.util.HashMap;

public interface NodesQueryInterface<Key, Value> {
    HashMap<Key, Value> getNodesFromMapExtract(int left, int top, int right, int bottom, String[] search_terms);
    void addListener(NodeReceiverInterface<OsmNode> irec);
    boolean nodeMatchesSearchTerms(OsmNode node, String[] search_terms);
}
