/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene.analysis;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.IntPair;
import alix.util.ObjRoll;

/**
 * A dictionary to record 
 */
public class CharsNet
{
  /** Record edge directions ? */
  final boolean directed;
  /** width of tokens to link between */
  final int width;
  /** last Node seen for the graph */
  private final ObjRoll<Node> nodeRoll;
  /** Set if node slider is full */
  private boolean nodeFull;
  /** Auto-increment node id */
  private int nodeAutoid;
  /** Dictionary of nodes */
  private HashMap<CharsAtt, Node> nodeHash = new HashMap<CharsAtt, Node>();
  /** Node index by id */
  private Node[] nodeList;
  /** Auto-increment edge id */
  private int edgeAutoid;
  /** Dictionary of edges */
  private HashMap<IntPair, Edge> edgeHash = new HashMap<IntPair, Edge>();
  /** Edge tester */
  private IntPair edgeKey = new IntPair();
  /** Node tester */
  private CharsAtt nodeKey = new CharsAtt();

  public CharsNet(final int width, final boolean directed)
  {
    this.directed = directed;
    if (width < 2)
      throw new IndexOutOfBoundsException("Width of nodes window should have 2 or more nodes to link between.");
    this.width = width;
    nodeRoll = new ObjRoll<Node>(1 - width, 0);
  }

  public void inc(final CharsAtt token)
  {
    inc(token, 0);
  }

  public void inc(final CharsAtt token, final int tag)
  {
    Node pivot = nodeHash.get(token);
    if (pivot == null) {
      CharsAtt key = new CharsAtt(token);
      pivot = new Node(key, tag);
      nodeHash.put(key, pivot);
      nodeList = null; // modification of nodeList
    }
    pivot.inc();
    nodeRoll.push(pivot);
    int nodes = width;
    if (!nodeFull) {
      nodes = nodeRoll.pushCount();
      if (nodes < 2) return; // not enough nodes
      if (nodes >= width) nodeFull = true; // roll is full
    }
    
    for (int i = 1 - nodes; i < 0; i++) {
      Node left = nodeRoll.get(i);
      // directed ?
      if (directed || left.id <  pivot.id) edgeKey.set(left.id, pivot.id);
      else edgeKey.set(pivot.id, left.id);
      Edge edge = edgeHash.get(edgeKey);
      if (edge == null) {
        if (directed || left.id <  pivot.id) edge = new Edge(left, pivot);
        else edge = new Edge(pivot, left);
        edgeHash.put(edgeKey, edge);
      }
      edge.inc();
    }
  }
  
  public Node node(final CharsAtt token)
  {
    return nodeHash.get(token);
  }

  public Node node(final String token)
  {
    nodeKey.setEmpty().append(token);
    return nodeHash.get(nodeKey);
  }

  public Node node(int id)
  {
    if (nodeList == null) {
      nodeList = new Node[nodeHash.size()];
      nodeHash.values().toArray(nodeList);
      Arrays.sort(nodeList, new Comparator<Node>() {
        @Override
        public int compare(Node o1, Node o2) {
           return Integer.compare(o1.id, o2.id);
        }
      });
    }
    return nodeList[id];
  }
  
  public Node[] nodes()
  {
    Node[] nodes = new Node[nodeHash.size()];
    nodeHash.values().toArray(nodes);
    Arrays.sort(nodes, new Comparator<Node>() {
      @Override
      public int compare(Node o1, Node o2) {
         return Integer.compare(o2.count, o1.count);
      }
    });
    return nodes;
  }

  public Edge[] edges()
  {
    Edge[] edges = new Edge[edgeHash.size()];
    edgeHash.values().toArray(edges);
    Arrays.sort(edges, new Comparator<Edge>() {
      @Override
      public int compare(Edge o1, Edge o2) {
         return Integer.compare(o2.count, o1.count);
      }
    });
    return edges;
  }

  public class Node
  {
    private final int id;
    private final CharsAtt label;
    private final int tag;
    private int count;
    private int count2;
    private boolean active;

    public Node(final CharsAtt label, final int tag)
    {
      this.label = new CharsAtt(label);
      this.tag = tag;
      this.id = nodeAutoid++;
    }

    public int id()
    {
      return id;
    }

    public CharsAtt label()
    {
      return label;
    }

    public int tag()
    {
      return tag;
    }

    public int count()
    {
      return count;
    }

    public int inc()
    {
      return ++count;
    }

    public int inc2()
    {
      return ++count2;
    }

    public void reset2()
    {
      this.count2 = 0;
    }

    public void active(final boolean active)
    {
      this.active = active;
    }

    public boolean active()
    {
      return this.active;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      sb.append(label);
      if (tag > 0) sb.append(" ").append(Tag.label(tag)).append(" ");
      sb.append(" (").append(count).append(")");
      return sb.toString();
    }
  }

  public class Edge
  {
    private final int id;
    private final Node source;
    private final Node target;
    private int count;

    public Edge(final Node source, final Node target)
    {
      this.id = ++edgeAutoid;
      this.source = source;
      this.target = target;
    }

    public Node source()
    {
      return source;
    }

    public Node target()
    {
      return target;
    }

    public int count()
    {
      return count;
    }

    public int id()
    {
      return id;
    }

    public int inc()
    {
      return ++count;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      sb.append(source.label());
      if (directed) sb.append(" -> ");
      else sb.append(" -- ");
      sb.append(target.label());
      sb.append(" (").append(count).append(")");
      return sb.toString();
    }
  }
}