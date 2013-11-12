/*
 *  Licensed to Peter Karich under one or more contributor license
 *  agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  Peter Karich licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the
 *  License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.graphhopper.coll.GHBitSet;
import com.graphhopper.coll.GHBitSetImpl;
import com.graphhopper.coll.SparseIntIntArray;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.search.NameIndex;
import com.graphhopper.util.*;
import static com.graphhopper.util.Helper.nf;
import com.graphhopper.util.shapes.BBox;

/**
 * The main implementation which handles nodes and edges file format. It can be used with different
 * Directory implementations like RAMDirectory for fast access or via MMapDirectory for
 * virtual-memory and not thread safe usage.
 * <p/>
 * Note: A RAM DataAccess Object is thread-safe in itself but if used in this Graph implementation
 * it is not write thread safe.
 * <p/>
 * Life cycle: (1) object creation, (2) configuration via setters & getters, (3) create or
 * loadExisting, (4) usage, (5) flush, (6) close
 * <p/>
 * @see GraphBuilderUsetheGraphBuilder class to create a (Level)GraphStorage easier.
 * @see LevelGraphStorage
 * @author Peter Karich
 */
public class GraphHopperStorage implements GraphStorage
{
    private static final int NO_NODE = -1;
    // Emergency stop. to detect if something went wrong with our storage system and to prevent us from an infinit loop.
    // Road networks typically do not have nodes with plenty of edges!
    private static final int MAX_EDGES = 1000;
    // distance of around +-1000 000 meter are ok
    private static final float INT_DIST_FACTOR = 1000f;
    private final Directory dir;
    // edge memory layout: nodeA,nodeB,linkA,linkB,dist,flags,geometryRef,streetNameRef
    protected final int E_NODEA, E_NODEB, E_LINKA, E_LINKB, E_DIST, E_FLAGS, E_GEO, E_NAME;
    protected int edgeEntryBytes;
    protected DataAccess edges;
    /**
     * Specified how many entries (integers) are used per edge. interval [0,n)
     */
    protected int edgeCount = 0;
    // node memory layout: edgeRef,lat,lon
    protected final int N_EDGE_REF, N_LAT, N_LON;
    /**
     * specified how many entries (integers) are used per node
     */
    protected int nodeEntryBytes;
    protected DataAccess nodes;
    /**
     * interval [0,n)
     */
    private int nodeCount;
    private BBox bounds;
    // remove markers are not yet persistent!
    private GHBitSet removedNodes;
    private int edgeEntryIndex = -4, nodeEntryIndex = -4;
    // length | nodeA | nextNode | ... | nodeB
    // as we use integer index in 'egdes' area => 'geometry' area is limited to 2GB
    private final DataAccess wayGeometry;
    // 0 stands for no separate geoRef
    private int maxGeoRef = 4;
    private boolean initialized = false;
    private EncodingManager encodingManager;
    private final NameIndex nameIndex;
    protected final EdgeFilter allEdgesFilter;
    private final StorableProperties properties;
    private final BitUtil bitUtil;

    public GraphHopperStorage( Directory dir, EncodingManager encodingManager )
    {
        // here encoding manager can be null e.g. if we want to load existing graph
        this.encodingManager = encodingManager;
        allEdgesFilter = EdgeFilter.ALL_EDGES;
        this.dir = dir;
        this.bitUtil = BitUtil.get(dir.getByteOrder());
        this.nodes = dir.find("nodes");
        this.edges = dir.find("edges");
        this.wayGeometry = dir.find("geometry");
        this.nameIndex = new NameIndex(dir);
        this.properties = new StorableProperties(dir);
        this.bounds = BBox.INVERSE.clone();
        E_NODEA = nextEdgeEntryIndex();
        E_NODEB = nextEdgeEntryIndex();
        E_LINKA = nextEdgeEntryIndex();
        E_LINKB = nextEdgeEntryIndex();
        E_DIST = nextEdgeEntryIndex();
        E_FLAGS = nextEdgeEntryIndex();
        E_GEO = nextEdgeEntryIndex();
        E_NAME = nextEdgeEntryIndex();

        N_EDGE_REF = nextNodeEntryIndex();
        N_LAT = nextNodeEntryIndex();
        N_LON = nextNodeEntryIndex();
        initNodeAndEdgeEntrySize();
    }

    void checkInit()
    {
        if (initialized)
        {
            throw new IllegalStateException("You cannot configure this GraphStorage "
                    + "after calling create or loadExisting. Calling one of the methods twice is also not allowed.");
        }
    }

    protected final int nextEdgeEntryIndex()
    {
        edgeEntryIndex += 4;
        return edgeEntryIndex;
    }

    protected final int nextNodeEntryIndex()
    {
        nodeEntryIndex += 4;
        return nodeEntryIndex;
    }

    protected final void initNodeAndEdgeEntrySize()
    {
        nodeEntryBytes = nodeEntryIndex + 4;
        edgeEntryBytes = edgeEntryIndex + 4;
    }

    /**
     * @return the directory where this graph is stored.
     */
    @Override
    public Directory getDirectory()
    {
        return dir;
    }

    @Override
    public void setSegmentSize( int bytes )
    {
        checkInit();
        nodes.setSegmentSize(bytes);
        edges.setSegmentSize(bytes);
        wayGeometry.setSegmentSize(bytes);
        nameIndex.setSegmentSize(bytes);
    }

    /**
     * After configuring this storage you need to create it explicitly.
     */
    @Override
    public GraphStorage create( long byteCount )
    {
        checkInit();
        if (encodingManager == null)
            throw new IllegalStateException("EncodingManager can only be null if you call loadExisting");

        long initSize = Math.max(byteCount, 100);
        nodes.create(initSize);
        initNodeRefs(0, nodes.getCapacity());

        edges.create(initSize);
        wayGeometry.create(initSize);
        nameIndex.create(1000);
        properties.create(100);
        properties.put("osmreader.acceptWay", encodingManager.encoderList());
        properties.putCurrentVersions();
        initialized = true;
        return this;
    }

    @Override
    public int getNodes()
    {
        return nodeCount;
    }

    @Override
    public double getLatitude( int index )
    {
        return Helper.intToDegree(nodes.getInt((long) index * nodeEntryBytes + N_LAT));
    }

    @Override
    public double getLongitude( int index )
    {
        return Helper.intToDegree(nodes.getInt((long) index * nodeEntryBytes + N_LON));
    }

    /**
     * Translates double VALUE to integer in order to save it in a DataAccess object
     */
    private int distToInt( double f )
    {
        return (int) (f * INT_DIST_FACTOR);
    }

    /**
     * returns distance (already translated from integer to double)
     */
    private double getDist( long pointer )
    {
        return (double) edges.getInt(pointer + E_DIST) / INT_DIST_FACTOR;
    }

    @Override
    public BBox getBounds()
    {
        return bounds;
    }

    @Override
    public void setNode( int index, double lat, double lon )
    {
        ensureNodeIndex(index);
        long tmp = (long) index * nodeEntryBytes;
        nodes.setInt(tmp + N_LAT, Helper.degreeToInt(lat));
        nodes.setInt(tmp + N_LON, Helper.degreeToInt(lon));
        if (lat > bounds.maxLat)
        {
            bounds.maxLat = lat;
        }
        if (lat < bounds.minLat)
        {
            bounds.minLat = lat;
        }
        if (lon > bounds.maxLon)
        {
            bounds.maxLon = lon;
        }
        if (lon < bounds.minLon)
        {
            bounds.minLon = lon;
        }
    }

    final void ensureNodeIndex( int nodeIndex )
    {
        if (nodeIndex < nodeCount)
            return;

        long oldNodes = nodeCount;
        nodeCount = nodeIndex + 1;
        if (!nodes.incCapacity((long) nodeCount * nodeEntryBytes))
            return;

        long newBytesCapacity = nodes.getCapacity();
        initNodeRefs(oldNodes * nodeEntryBytes, newBytesCapacity);
        if (removedNodes != null)
            getRemovedNodes().ensureCapacity((int) (newBytesCapacity / nodeEntryBytes));
    }

    /**
     * Initializes the node area with the empty edge value.
     */
    private void initNodeRefs( long oldCapacity, long newCapacity )
    {
        for (long pointer = oldCapacity + N_EDGE_REF; pointer < newCapacity; pointer += nodeEntryBytes)
        {
            nodes.setInt(pointer, EdgeIterator.NO_EDGE);
        }
    }

    private void ensureEdgeIndex( int edgeIndex )
    {
        edges.incCapacity(((long) edgeIndex + 1) * edgeEntryBytes);
    }

    private void ensureGeometry( long bytePos, int byteLength )
    {
        wayGeometry.incCapacity(bytePos + byteLength);
    }

    @Override
    public EdgeIterator edge( int a, int b, double distance, boolean bothDirections )
    {
        return edge(a, b, distance, encodingManager.flagsDefault(bothDirections));
    }

    @Override
    public EdgeIterator edge( int a, int b, double distance, int flags )
    {
        ensureNodeIndex(Math.max(a, b));
        int edge = internalEdgeAdd(a, b, distance, flags);
        EdgeIterable iter = new EdgeIterable(EdgeFilter.ALL_EDGES);
        iter.setBaseNode(a);
        iter.setEdgeId(edge);
        iter.next();
        return iter;
    }

    private int nextGeoRef( int arrayLength )
    {
        int tmp = maxGeoRef;
        // one more integer to store also the size itself
        maxGeoRef += arrayLength + 1;
        return tmp;
    }

    /**
     * @return edgeIdPointer which is edgeId * edgeEntrySize
     */
    int internalEdgeAdd( int fromNodeId, int toNodeId, double dist, int flags )
    {
        int newOrExistingEdge = nextEdge();
        writeEdge(newOrExistingEdge, fromNodeId, toNodeId, EdgeIterator.NO_EDGE, EdgeIterator.NO_EDGE, dist, flags);
        connectNewEdge(fromNodeId, newOrExistingEdge);
        if (fromNodeId != toNodeId)
            connectNewEdge(toNodeId, newOrExistingEdge);

        return newOrExistingEdge;
    }

    // for test only
    void setEdgeCount( int cnt )
    {
        edgeCount = cnt;
    }

    private int nextEdge()
    {
        int nextEdge = edgeCount;
        edgeCount++;
        if (edgeCount < 0)
            throw new IllegalStateException("too many edges. new edge id would be negative. " + toString());

        ensureEdgeIndex(edgeCount);
        return nextEdge;
    }

    private void connectNewEdge( int fromNode, int newOrExistingEdge )
    {
        long nodePointer = (long) fromNode * nodeEntryBytes;
        int edge = nodes.getInt(nodePointer + N_EDGE_REF);
        if (edge > EdgeIterator.NO_EDGE)
        {
            long edgePointer = (long) newOrExistingEdge * edgeEntryBytes;
            int otherNode = getOtherNode(fromNode, edgePointer);
            long lastLink = getLinkPosInEdgeArea(fromNode, otherNode, edgePointer);
            edges.setInt(lastLink, edge);
        }

        nodes.setInt(nodePointer + N_EDGE_REF, newOrExistingEdge);
    }

    private long writeEdge( int edge, int nodeThis, int nodeOther, int nextEdge, int nextEdgeOther,
            double distance, int flags )
    {
        if (nodeThis > nodeOther)
        {
            int tmp = nodeThis;
            nodeThis = nodeOther;
            nodeOther = tmp;

            tmp = nextEdge;
            nextEdge = nextEdgeOther;
            nextEdgeOther = tmp;

            flags = encodingManager.swapDirection(flags);
        }

        long edgePointer = (long) edge * edgeEntryBytes;
        edges.setInt(edgePointer + E_NODEA, nodeThis);
        edges.setInt(edgePointer + E_NODEB, nodeOther);
        edges.setInt(edgePointer + E_LINKA, nextEdge);
        edges.setInt(edgePointer + E_LINKB, nextEdgeOther);
        edges.setInt(edgePointer + E_DIST, distToInt(distance));
        edges.setInt(edgePointer + E_FLAGS, flags);
        return edgePointer;
    }

    protected final long getLinkPosInEdgeArea( int nodeThis, int nodeOther, long edgePointer )
    {
        return nodeThis <= nodeOther ? edgePointer + E_LINKA : edgePointer + E_LINKB;
    }

    public String getDebugInfo( int node, int area )
    {
        String str = "--- node " + node + " ---";
        int min = Math.max(0, node - area / 2);
        int max = Math.min(nodeCount, node + area / 2);
        long nodePointer = (long) node * nodeEntryBytes;
        for (int i = min; i < max; i++)
        {
            str += "\n" + i + ": ";
            for (int j = 0; j < nodeEntryBytes; j += 4)
            {
                if (j > 0)
                {
                    str += ",\t";
                }
                str += nodes.getInt(nodePointer + j);
            }
        }
        int edge = nodes.getInt(nodePointer);
        str += "\n--- edges " + edge + " ---";
        int otherNode;
        for (int i = 0; i < 1000; i++)
        {
            str += "\n";
            if (edge == EdgeIterator.NO_EDGE)
                break;

            str += edge + ": ";
            long edgePointer = (long) edge * edgeEntryBytes;
            for (int j = 0; j < edgeEntryBytes; j += 4)
            {
                if (j > 0)
                {
                    str += ",\t";
                }
                str += edges.getInt(edgePointer + j);
            }

            otherNode = getOtherNode(node, edgePointer);
            long lastLink = getLinkPosInEdgeArea(node, otherNode, edgePointer);
            edge = edges.getInt(lastLink);
        }
        return str;
    }

    private int getOtherNode( int nodeThis, long edgePointer )
    {
        int nodeA = edges.getInt(edgePointer + E_NODEA);
        if (nodeA == nodeThis)
        // return b
        {
            return edges.getInt(edgePointer + E_NODEB);
        }
        // return a
        return nodeA;
    }

    @Override
    public AllEdgesIterator getAllEdges()
    {
        return new AllEdgeIterator();
    }

    @Override
    public EncodingManager getEncodingManager()
    {
        return encodingManager;
    }

    @Override
    public StorableProperties getProperties()
    {
        return properties;
    }

    /**
     * Include all edges of this storage in the iterator.
     */
    protected class AllEdgeIterator implements AllEdgesIterator
    {
        protected long edgePointer = -edgeEntryBytes;
        private final long maxEdges = (long) edgeCount * edgeEntryBytes;
        private int nodeA;

        public AllEdgeIterator()
        {
        }

        @Override
        public int getMaxId()
        {
            return edgeCount;
        }

        @Override
        public boolean next()
        {
            do
            {
                edgePointer += edgeEntryBytes;
                nodeA = edges.getInt(edgePointer + E_NODEA);
                // some edges are deleted and have a negative node
            } while (nodeA == NO_NODE && edgePointer < maxEdges);
            return edgePointer < maxEdges;
        }

        @Override
        public int getBaseNode()
        {
            return nodeA;
        }

        @Override
        public int getAdjNode()
        {
            return edges.getInt(edgePointer + E_NODEB);
        }

        @Override
        public double getDistance()
        {
            return getDist(edgePointer);
        }

        @Override
        public void setDistance( double dist )
        {
            edges.setInt(edgePointer + E_DIST, distToInt(dist));
        }

        @Override
        public int getFlags()
        {
            return edges.getInt(edgePointer + E_FLAGS);
        }

        @Override
        public void setFlags( int flags )
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int getEdge()
        {
            return (int) (edgePointer / edgeEntryBytes);
        }

        @Override
        public void setWayGeometry( PointList pillarNodes )
        {
            GraphHopperStorage.this.setWayGeometry(pillarNodes, edgePointer, getBaseNode() > getAdjNode());
        }

        @Override
        public PointList fetchWayGeometry( int type )
        {
            return GraphHopperStorage.this.fetchWayGeometry(edgePointer, getBaseNode() > getAdjNode(), type, getBaseNode(), getAdjNode());
        }

        @Override
        public String getName()
        {
            int nameIndexRef = edges.getInt(edgePointer + E_NAME);
            return nameIndex.get(nameIndexRef);
        }

        @Override
        public void setName( String name )
        {
            int nameIndexRef = nameIndex.put(name);
            edges.setInt(edgePointer + E_NAME, nameIndexRef);
        }

        @Override
        public EdgeIteratorState detach()
        {
            if (edgePointer < 0)
                throw new IllegalStateException("call next before detaching");
            AllEdgeIterator iter = new AllEdgeIterator();
            iter.edgePointer = edgePointer;
            iter.nodeA = nodeA;
            return iter;
        }

        @Override
        public String toString()
        {
            return getEdge() + " " + getBaseNode() + "-" + getAdjNode();
        }
    }

    @Override
    public EdgeIteratorState getEdgeProps( int edgeId, int adjNode )
    {
        if (edgeId <= EdgeIterator.NO_EDGE || edgeId >= edgeCount)
            throw new IllegalStateException("edgeId " + edgeId + " out of bounds [0," + nf(edgeCount) + "]");

        // -1 no longer supported
        if (adjNode < 0)
            throw new IllegalStateException("adjNode " + adjNode + " out of bounds [0," + nf(nodeCount) + "]");

        long edgePointer = (long) edgeId * edgeEntryBytes;
        int nodeA = edges.getInt(edgePointer + E_NODEA);
        if (nodeA == NO_NODE)
            throw new IllegalStateException("edgeId " + edgeId + " is invalid - already removed!");

        int nodeB = edges.getInt(edgePointer + E_NODEB);
        SingleEdge edge;
        if (adjNode == nodeB)
        {
            edge = createSingleEdge(edgeId, nodeA);
            edge.node = nodeB;
            return edge;
        } else if (adjNode == nodeA)
        {
            edge = createSingleEdge(edgeId, nodeB);
            edge.node = nodeA;
            edge.switchFlags = true;
            return edge;
        }
        // if edgeId exists but adjacent nodes do not match
        return null;
    }

    protected SingleEdge createSingleEdge( int edgeId, int nodeId )
    {
        return new SingleEdge(edgeId, nodeId);
    }

    protected class SingleEdge extends EdgeIterable
    {
        protected boolean switchFlags;

        public SingleEdge( int edgeId, int nodeId )
        {
            super(EdgeFilter.ALL_EDGES);
            setBaseNode(nodeId);
            setEdgeId(edgeId);
            nextEdge = EdgeIterable.NO_EDGE;
        }

        @Override
        public int getFlags()
        {
            int flags = edges.getInt(edgePointer + E_FLAGS);
            if (switchFlags)
                return encodingManager.swapDirection(flags);

            return flags;
        }
    }

    @Override
    public EdgeExplorer createEdgeExplorer( EdgeFilter filter )
    {
        return new EdgeIterable(filter);
    }

    @Override
    public EdgeExplorer createEdgeExplorer()
    {
        return createEdgeExplorer(allEdgesFilter);
    }

    protected class EdgeIterable implements EdgeExplorer, EdgeIterator
    {
        final EdgeFilter filter;
        int baseNode;
        // edge properties
        int node;
        int edgeId;
        long edgePointer;
        int nextEdge;

        // used for SingleEdge and as return value of edge()        
        public EdgeIterable( EdgeFilter filter )
        {
            if (filter == null)
                throw new IllegalArgumentException("Instead null filter use EdgeFilter.ALL_EDGES");

            this.filter = filter;
        }

        protected void setEdgeId( int edgeId )
        {
            this.nextEdge = this.edgeId = edgeId;
            this.edgePointer = (long) nextEdge * edgeEntryBytes;
        }

        @Override
        public EdgeIterator setBaseNode( int baseNode )
        {
            int edge = nodes.getInt((long) baseNode * nodeEntryBytes + N_EDGE_REF);
            setEdgeId(edge);
            this.baseNode = baseNode;
            return this;
        }

        @Override
        public final boolean next()
        {
            int i = 0;
            boolean foundNext = false;
            for (; i < MAX_EDGES; i++)
            {
                if (nextEdge == EdgeIterator.NO_EDGE)
                    break;

                edgePointer = (long) nextEdge * edgeEntryBytes;
                edgeId = nextEdge;
                node = getOtherNode(baseNode, edgePointer);

                // position to next edge
                nextEdge = edges.getInt(getLinkPosInEdgeArea(baseNode, node, edgePointer));
                if (nextEdge == edgeId)
                    throw new AssertionError("endless loop detected for " + baseNode + ", " + node
                            + ", " + edgePointer + ", " + edgeId);

                foundNext = filter == null || filter.accept(this);
                if (foundNext)
                    break;
            }

            if (i > MAX_EDGES)
                throw new IllegalStateException("something went wrong: no end of edge-list found");

            return foundNext;
        }

        private long getEdgePointer()
        {
            return edgePointer;
        }

        @Override
        public final int getAdjNode()
        {
            return node;
        }

        @Override
        public final double getDistance()
        {
            return getDist(edgePointer);
        }

        @Override
        public final void setDistance( double dist )
        {
            edges.setInt(edgePointer + E_DIST, distToInt(dist));
        }

        @Override
        public int getFlags()
        {
            int flags = edges.getInt(edgePointer + E_FLAGS);

            // switch direction flags if necessary
            if (baseNode > node)
                flags = encodingManager.swapDirection(flags);

            return flags;
        }

        @Override
        public final void setFlags( int fl )
        {
            int nep = edges.getInt(getLinkPosInEdgeArea(baseNode, node, edgePointer));
            int neop = edges.getInt(getLinkPosInEdgeArea(node, baseNode, edgePointer));
            writeEdge(getEdge(), baseNode, node, nep, neop, getDistance(), fl);
        }

        @Override
        public final int getBaseNode()
        {
            return baseNode;
        }

        @Override
        public final void setWayGeometry( PointList pillarNodes )
        {
            GraphHopperStorage.this.setWayGeometry(pillarNodes, edgePointer, baseNode > node);
        }

        @Override
        public final PointList fetchWayGeometry( int mode )
        {
            return GraphHopperStorage.this.fetchWayGeometry(edgePointer, baseNode > node, mode, getBaseNode(), getAdjNode());
        }

        @Override
        public final int getEdge()
        {
            return edgeId;
        }

        @Override
        public String getName()
        {
            int nameIndexRef = edges.getInt(edgePointer + E_NAME);
            return nameIndex.get(nameIndexRef);
        }

        @Override
        public void setName( String name )
        {
            int nameIndexRef = nameIndex.put(name);
            edges.setInt(edgePointer + E_NAME, nameIndexRef);
        }

        @Override
        public EdgeIterator detach()
        {
            if (edgeId == nextEdge)
                throw new IllegalStateException("call next before detaching");

            EdgeIterable iter = new EdgeIterable(filter);
            iter.setBaseNode(baseNode);
            iter.setEdgeId(edgeId);
            iter.next();
            return iter;
        }

        @Override
        public final String toString()
        {
            return getEdge() + " " + getBaseNode() + "-" + getAdjNode();
        }
    }

    private void setWayGeometry( PointList pillarNodes, long edgePointer, boolean reverse )
    {
        if (pillarNodes != null && !pillarNodes.isEmpty())
        {
            int len = pillarNodes.getSize();
            int tmpRef = nextGeoRef(len * 2);
            edges.setInt(edgePointer + E_GEO, tmpRef);
            long geoRef = (long) tmpRef * 4;
            ensureGeometry(geoRef, len * 8 + 4);
            byte[] bytes = new byte[len * 2 * 4 + 4];
            bitUtil.fromInt(bytes, len, 0);
            if (reverse)
                pillarNodes.reverse();

            int tmpOffset = 4;
            for (int i = 0; i < len; i++)
            {
                bitUtil.fromInt(bytes, Helper.degreeToInt(pillarNodes.getLatitude(i)), tmpOffset);
                tmpOffset += 4;
                bitUtil.fromInt(bytes, Helper.degreeToInt(pillarNodes.getLongitude(i)), tmpOffset);
                tmpOffset += 4;
            }

            wayGeometry.setBytes(geoRef, bytes, bytes.length);
        } else
        {
            edges.setInt(edgePointer + E_GEO, 0);
        }
    }

    private PointList fetchWayGeometry( long edgePointer, boolean reverse, int mode, int baseNode, int adjNode )
    {
        long geoRef = edges.getInt(edgePointer + E_GEO);
        int count = 0;
        byte[] bytes = null;
        if (geoRef > 0)
        {
            geoRef *= 4;
            count = wayGeometry.getInt(geoRef);
            wayGeometry.getInt(geoRef);

            geoRef += 4;
            bytes = new byte[count * 2 * 4];
            wayGeometry.getBytes(geoRef, bytes, bytes.length);
        } else if (mode == 0)
            return PointList.EMPTY;

        PointList pillarNodes = new PointList(count + mode);
        if (reverse)
        {
            if ((mode & 2) != 0)
                pillarNodes.add(getLatitude(adjNode), getLongitude(adjNode));
        } else
        {
            if ((mode & 1) != 0)
                pillarNodes.add(getLatitude(baseNode), getLongitude(baseNode));
        }

        int index = 0;
        for (int i = 0; i < count; i++)
        {
            double lat = Helper.intToDegree(bitUtil.toInt(bytes, index));
            index += 4;
            double lon = Helper.intToDegree(bitUtil.toInt(bytes, index));
            index += 4;
            pillarNodes.add(lat, lon);
        }

        if (reverse)
        {
            if ((mode & 1) != 0)
                pillarNodes.add(getLatitude(baseNode), getLongitude(baseNode));
            pillarNodes.reverse();
        } else
        {
            if ((mode & 2) != 0)
                pillarNodes.add(getLatitude(adjNode), getLongitude(adjNode));
        }

        return pillarNodes;
    }

    @Override
    public Graph copyTo( Graph g )
    {
        if (g.getClass().equals(getClass()))
        {
            return _copyTo((GraphHopperStorage) g);
        } else
        {
            return GHUtility.copyTo(this, g);
        }
    }

    Graph _copyTo( GraphHopperStorage clonedG )
    {
        if (clonedG.edgeEntryBytes != edgeEntryBytes)
        {
            throw new IllegalStateException("edgeEntrySize cannot be different for cloned graph");
        }

        if (clonedG.nodeEntryBytes != nodeEntryBytes)
        {
            throw new IllegalStateException("nodeEntrySize cannot be different for cloned graph");
        }

        // nodes
        nodes.copyTo(clonedG.nodes);
        clonedG.nodeCount = nodeCount;
        clonedG.bounds = bounds.clone();

        // edges
        edges.copyTo(clonedG.edges);
        clonedG.edgeCount = edgeCount;

        // name
        nameIndex.copyTo(clonedG.nameIndex);

        // geometry
        wayGeometry.copyTo(clonedG.wayGeometry);
        clonedG.maxGeoRef = maxGeoRef;

        properties.copyTo(clonedG.properties);

        if (removedNodes == null)
            clonedG.removedNodes = null;
        else
            clonedG.removedNodes = removedNodes.copyTo(new GHBitSetImpl());

        clonedG.encodingManager = encodingManager;
        initialized = true;
        return clonedG;
    }

    private GHBitSet getRemovedNodes()
    {
        if (removedNodes == null)
            removedNodes = new GHBitSetImpl((int) (nodes.getCapacity() / 4));

        return removedNodes;
    }

    @Override
    public void markNodeRemoved( int index )
    {
        getRemovedNodes().add(index);
    }

    @Override
    public boolean isNodeRemoved( int index )
    {
        return getRemovedNodes().contains(index);
    }

    @Override
    public void optimize()
    {
        int delNodes = getRemovedNodes().getCardinality();
        if (delNodes <= 0)
            return;

        // Deletes only nodes. 
        // It reduces the fragmentation of the node space but introduces new unused edges.
        inPlaceNodeRemove(delNodes);

        // Reduce memory usage
        trimToSize();
    }

    private void trimToSize()
    {
        long nodeCap = (long) nodeCount * nodeEntryBytes;
        nodes.trimTo(nodeCap);
//        long edgeCap = (long) (edgeCount + 1) * edgeEntrySize;
//        edges.trimTo(edgeCap * 4);
    }

    /**
     * This method disconnects the specified edge from the list of edges of the specified node. It
     * does not release the freed space to be reused.
     * <p/>
     * @param edgeToUpdatePointer if it is negative then the nextEdgeId will be saved to refToEdges
     * of nodes
     */
    long internalEdgeDisconnect( int edgeToRemove, long edgeToUpdatePointer, int baseNode, int adjNode )
    {
        long edgeToRemovePointer = (long) edgeToRemove * edgeEntryBytes;
        // an edge is shared across the two nodes even if the edge is not in both directions
        // so we need to know two edge-pointers pointing to the edge before edgeToRemovePointer
        int nextEdgeId = edges.getInt(getLinkPosInEdgeArea(baseNode, adjNode, edgeToRemovePointer));
        if (edgeToUpdatePointer < 0)
        {
            nodes.setInt((long) baseNode * nodeEntryBytes, nextEdgeId);
        } else
        {
            // adjNode is different for the edge we want to update with the new link
            long link = edges.getInt(edgeToUpdatePointer + E_NODEA) == baseNode
                    ? edgeToUpdatePointer + E_LINKA : edgeToUpdatePointer + E_LINKB;
            edges.setInt(link, nextEdgeId);
        }
        return edgeToRemovePointer;
    }

    private void invalidateEdge( long edgePointer )
    {
        edges.setInt(edgePointer + E_NODEA, NO_NODE);
    }

    /**
     * This methods disconnects all edges from removed nodes. It does no edge compaction. Then it
     * moves the last nodes into the deleted nodes, where it needs to update the node ids in every
     * edge.
     */
    private void inPlaceNodeRemove( int removeNodeCount )
    {
        // Prepare edge-update of nodes which are connected to deleted nodes        
        int toMoveNode = getNodes();
        int itemsToMove = 0;

        // sorted map when we access it via keyAt and valueAt - see below!
        final SparseIntIntArray oldToNewMap = new SparseIntIntArray(removeNodeCount);
        GHBitSet toRemoveSet = new GHBitSetImpl(removeNodeCount);
        removedNodes.copyTo(toRemoveSet);

        EdgeExplorer delExplorer = createEdgeExplorer(allEdgesFilter);
        // create map of old node ids pointing to new ids        
        for (int removeNode = removedNodes.next(0);
                removeNode >= 0;
                removeNode = removedNodes.next(removeNode + 1))
        {
            EdgeIterator delEdgesIter = delExplorer.setBaseNode(removeNode);
            while (delEdgesIter.next())
            {
                toRemoveSet.add(delEdgesIter.getAdjNode());
            }

            toMoveNode--;
            for (; toMoveNode >= 0; toMoveNode--)
            {
                if (!removedNodes.contains(toMoveNode))
                    break;
            }

            if (toMoveNode >= removeNode)
                oldToNewMap.put(toMoveNode, removeNode);

            itemsToMove++;
        }

        EdgeIterable adjNodesToDelIter = (EdgeIterable) createEdgeExplorer();
        // now similar process to disconnectEdges but only for specific nodes
        // all deleted nodes could be connected to existing. remove the connections
        for (int removeNode = toRemoveSet.next(0);
                removeNode >= 0;
                removeNode = toRemoveSet.next(removeNode + 1))
        {
            // remove all edges connected to the deleted nodes
            adjNodesToDelIter.setBaseNode(removeNode);
            long prev = EdgeIterator.NO_EDGE;
            while (adjNodesToDelIter.next())
            {
                int nodeId = adjNodesToDelIter.getAdjNode();
                // already invalidated
                if (nodeId != NO_NODE && removedNodes.contains(nodeId))
                {
                    int edgeToRemove = adjNodesToDelIter.getEdge();
                    long edgeToRemovePointer = (long) edgeToRemove * edgeEntryBytes;
                    internalEdgeDisconnect(edgeToRemove, prev, removeNode, nodeId);
                    invalidateEdge(edgeToRemovePointer);
                } else
                {
                    prev = adjNodesToDelIter.getEdgePointer();
                }
            }
        }

        GHBitSet toMoveSet = new GHBitSetImpl(removeNodeCount * 3);
        EdgeExplorer movedEdgeExplorer = createEdgeExplorer();
        // marks connected nodes to rewrite the edges
        for (int i = 0; i < itemsToMove; i++)
        {
            int oldI = oldToNewMap.keyAt(i);
            EdgeIterator movedEdgeIter = movedEdgeExplorer.setBaseNode(oldI);
            while (movedEdgeIter.next())
            {
                int nodeId = movedEdgeIter.getAdjNode();
                if (nodeId == NO_NODE)
                    continue;

                if (removedNodes.contains(nodeId))
                    throw new IllegalStateException("shouldn't happen the edge to the node "
                            + nodeId + " should be already deleted. " + oldI);

                toMoveSet.add(nodeId);
            }
        }

        // move nodes into deleted nodes
        for (int i = 0; i < itemsToMove; i++)
        {
            int oldI = oldToNewMap.keyAt(i);
            int newI = oldToNewMap.valueAt(i);
            long newOffset = (long) newI * nodeEntryBytes;
            long oldOffset = (long) oldI * nodeEntryBytes;
            for (long j = 0; j < nodeEntryBytes; j += 4)
            {
                nodes.setInt(newOffset + j, nodes.getInt(oldOffset + j));
            }
        }

        // *rewrites* all edges connected to moved nodes
        // go through all edges and pick the necessary ... <- this is easier to implement then
        // a more efficient (?) breadth-first search
        EdgeIterator iter = getAllEdges();
        while (iter.next())
        {
            int nodeA = iter.getBaseNode();
            int nodeB = iter.getAdjNode();
            if (!toMoveSet.contains(nodeA) && !toMoveSet.contains(nodeB))
                continue;

            // now overwrite exiting edge with new node ids 
            // also flags and links could have changed due to different node order
            int updatedA = oldToNewMap.get(nodeA);
            if (updatedA < 0)
                updatedA = nodeA;

            int updatedB = oldToNewMap.get(nodeB);
            if (updatedB < 0)
                updatedB = nodeB;

            int edge = iter.getEdge();
            long edgePointer = (long) edge * edgeEntryBytes;
            int linkA = edges.getInt(getLinkPosInEdgeArea(nodeA, nodeB, edgePointer));
            int linkB = edges.getInt(getLinkPosInEdgeArea(nodeB, nodeA, edgePointer));
            int flags = edges.getInt(edgePointer + E_FLAGS);
            double distance = getDist(edgePointer);
            writeEdge(edge, updatedA, updatedB, linkA, linkB, distance, flags);
            if (updatedA < updatedB != nodeA < nodeB)
                setWayGeometry(fetchWayGeometry(edgePointer, true, 0, -1, -1), edgePointer, false);
        }

        // we do not remove the invalid edges => edgeCount stays the same!
        nodeCount -= removeNodeCount;

        EdgeExplorer explorer = createEdgeExplorer();
        // health check
        if (isTestingEnabled())
        {
            iter = getAllEdges();
            while (iter.next())
            {
                int base = iter.getBaseNode();
                int adj = iter.getAdjNode();
                String str = iter.getEdge()
                        + ", r.contains(" + base + "):" + removedNodes.contains(base)
                        + ", r.contains(" + adj + "):" + removedNodes.contains(adj)
                        + ", tr.contains(" + base + "):" + toRemoveSet.contains(base)
                        + ", tr.contains(" + adj + "):" + toRemoveSet.contains(adj)
                        + ", base:" + base + ", adj:" + adj + ", nodeCount:" + nodeCount;
                if (adj >= nodeCount)
                    throw new RuntimeException("Adj.node problem with edge " + str);

                if (base >= nodeCount)
                    throw new RuntimeException("Base node problem with edge " + str);

                try
                {
                    explorer.setBaseNode(adj).toString();
                } catch (Exception ex)
                {
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("adj:" + adj);
                }
                try
                {
                    explorer.setBaseNode(base).toString();
                } catch (Exception ex)
                {
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("base:" + base);
                }
            }
            // access last node -> no error
            explorer.setBaseNode(nodeCount - 1).toString();
        }
        removedNodes = null;
    }

    private static boolean isTestingEnabled()
    {
        boolean enableIfAssert = false;
        assert (enableIfAssert = true) : true;
        return enableIfAssert;
    }

    @Override
    public boolean loadExisting()
    {
        checkInit();
        if (edges.loadExisting())
        {
            if (!nodes.loadExisting())
                throw new IllegalStateException("cannot load nodes. corrupt file or directory? " + dir);

            if (!wayGeometry.loadExisting())
                throw new IllegalStateException("cannot load geometry. corrupt file or directory? " + dir);

            if (!nameIndex.loadExisting())
                throw new IllegalStateException("cannot load name index. corrupt file or directory? " + dir);

            String acceptStr = "";
            if (properties.loadExisting())
            {
                properties.checkVersions(false);
                // check encoding for compatiblity
                acceptStr = properties.get("osmreader.acceptWay");
            } else
                throw new IllegalStateException("cannot load properties. corrupt file or directory? " + dir);

            if (encodingManager == null)
            {
                if (acceptStr.isEmpty())
                    throw new IllegalStateException("No EncodingManager was configured. And no one was found in the graph: " + dir.getLocation());

                encodingManager = new EncodingManager(acceptStr);
            } else if (!acceptStr.isEmpty() && !encodingManager.encoderList().equalsIgnoreCase(acceptStr))
            {
                throw new IllegalStateException("Encoding does not match:\nGraphhopper config: " + encodingManager.encoderList() + "\nGraph: " + acceptStr);
            }

            // nodes
            int hash = nodes.getHeader(0);
            if (hash != getClass().getName().hashCode())
            {
                throw new IllegalStateException("Cannot load the graph - it wasn't create via "
                        + getClass().getName() + "! " + dir);
            }
            nodeEntryBytes = nodes.getHeader(1 * 4);
            nodeCount = nodes.getHeader(2 * 4);
            bounds.minLon = Helper.intToDegree(nodes.getHeader(3 * 4));
            bounds.maxLon = Helper.intToDegree(nodes.getHeader(4 * 4));
            bounds.minLat = Helper.intToDegree(nodes.getHeader(5 * 4));
            bounds.maxLat = Helper.intToDegree(nodes.getHeader(6 * 4));

            // edges
            edgeEntryBytes = edges.getHeader(0);
            edgeCount = edges.getHeader(1 * 4);

            // geometry
            maxGeoRef = wayGeometry.getHeader(0);
            initialized = true;
            return true;
        }
        return false;
    }

    @Override
    public void flush()
    {
        // nodes
        nodes.setHeader(0, getClass().getName().hashCode());
        nodes.setHeader(1 * 4, nodeEntryBytes);
        nodes.setHeader(2 * 4, nodeCount);
        nodes.setHeader(3 * 4, Helper.degreeToInt(bounds.minLon));
        nodes.setHeader(4 * 4, Helper.degreeToInt(bounds.maxLon));
        nodes.setHeader(5 * 4, Helper.degreeToInt(bounds.minLat));
        nodes.setHeader(6 * 4, Helper.degreeToInt(bounds.maxLat));

        // edges
        edges.setHeader(0, edgeEntryBytes);
        edges.setHeader(1 * 4, edgeCount);
        edges.setHeader(2 * 4, encodingManager.hashCode());

        // geometry
        wayGeometry.setHeader(0, maxGeoRef);

        properties.flush();
        wayGeometry.flush();
        nameIndex.flush();
        edges.flush();
        nodes.flush();
    }

    @Override
    public void close()
    {
        properties.close();
        wayGeometry.close();
        nameIndex.close();
        edges.close();
        nodes.close();
    }

    @Override
    public long getCapacity()
    {
        return edges.getCapacity() + nodes.getCapacity() + nameIndex.getCapacity()
                + wayGeometry.getCapacity() + properties.getCapacity();
    }

    public String toDetailsString()
    {
        return "edges:" + nf(edgeCount) + "(" + edges.getCapacity() / Helper.MB + "), "
                + "nodes:" + nf(nodeCount) + "(" + nodes.getCapacity() / Helper.MB + "), "
                + "name: - (" + nameIndex.getCapacity() / Helper.MB + "), "
                + "geo:" + nf(maxGeoRef) + "(" + wayGeometry.getCapacity() / Helper.MB + "), "
                + "bounds:" + bounds;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName()
                + "|" + encodingManager
                + "|" + getDirectory().getDefaultType()
                + "|" + getProperties().versionsToString();
    }
}
