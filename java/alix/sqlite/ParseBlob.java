/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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
package alix.sqlite;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.util.DicFreq;
import alix.util.DicFreq.Entry;
import alix.util.Occ;

public class ParseBlob implements Runnable
{
  /** Sqlite connexion */
  private static Connection texts;
  /** Sqlite connexion */
  private static Connection index;
  /** Dictionary of orthographic form with an index */
  private static DicFreq orthDic;
  /** Dictionary of orthographic form with an index */
  private static DicFreq lemDic;
  /** Number of pages */
  private static int pageCount;
  /** Local cursor on blob to process */
  private ResultSet pages;
  /** Start index in document list */
  private final int start;

  /**
   * Open database before ops, and load dictionaries.
   * 
   * @throws SQLException
   */
  public static int open(String textBase, String indexBase) throws SQLException
  {
    index = DriverManager.getConnection("jdbc:sqlite:" + indexBase);
    // occs.createStatement().execute("PRAGMA locking_mode = EXCLUSIVE;");

    texts = DriverManager.getConnection("jdbc:sqlite:" + textBase);
    ResultSet res = texts.createStatement().executeQuery("SELECT MAX(id)+1 FROM blob");
    pageCount = res.getInt(1);
    res.close();

    Statement stmt = index.createStatement();
    // load orth dic
    orthDic = new DicFreq();
    res = stmt.executeQuery("SELECT * FROM orth ORDER BY id");
    while (res.next()) {
      orthDic.put(res.getString("form"), res.getInt("tag"), res.getInt("lem"));
    }
    res.close();
    lemDic = new DicFreq();
    res = stmt.executeQuery("SELECT * FROM lem ORDER BY id");
    while (res.next()) {
      lemDic.put(res.getString("form"), res.getInt("tag"));
    }

    res.close();
    return pageCount;
  }

  public static int pageCount()
  {
    return pageCount;
  }

  /**
   * Close database and update dictionaries.
   * 
   * @throws SQLException
   */
  public static void close() throws SQLException
  {
    texts.close();
    PreparedStatement stmt;
    index.setAutoCommit(false);
    stmt = index.prepareStatement("DELETE FROM orth");
    stmt.execute();
    stmt = index.prepareStatement("INSERT INTO orth(id, form, tag, lem) VALUES (?, ?, ?, ?)");
    for (Entry entry : orthDic.entries()) {
      if (entry == null) break; // last one
      stmt.setInt(1, entry.code());
      stmt.setString(2, entry.label());
      stmt.setInt(3, entry.tag());
      stmt.setInt(4, entry.count());
      stmt.execute();
    }
    stmt.close();
    stmt = index.prepareStatement("DELETE FROM lem");
    stmt.execute();
    stmt = index.prepareStatement("INSERT INTO lem(id, form, tag) VALUES (?, ?, ?)");
    for (Entry entry : lemDic.entries()) {
      if (entry == null) break; // last one
      stmt.setInt(1, entry.code());
      stmt.setString(2, entry.label());
      stmt.setInt(3, entry.tag());
      stmt.execute();
    }
    index.commit();
    // stmt.execute("PRAGMA locking_mode = NORMAL;");
    stmt.close();
    index.close();
  }

  /**
   * Constructor of the thread
   * 
   * @param offset
   * @param limit
   * @throws SQLException
   */
  public ParseBlob(final int start, final int end) throws SQLException
  {
    this.start = start;
    PreparedStatement q = texts.prepareStatement("SELECT id, text FROM blob WHERE id >= ? AND id < ?");
    q.setInt(1, start);
    q.setInt(2, end);
    pages = q.executeQuery();
  }

  /**
   * Parse record
   * 
   * @throws SQLException
   */
  public void run()
  {
    // String table = Thread.currentThread().getName().replaceAll("-", "");
    String table = "table" + start;
    try {
      Statement stmt = index.createStatement();
      stmt.execute("CREATE TEMP TABLE '" + table + "' (doc, orth, tag, lem, start, end)");
      stmt.close();
      PreparedStatement ins = index.prepareStatement(
          "INSERT INTO '" + table + "'" + " (doc, orth, tag, lem, start, end)" + " VALUES (?, ?, ?, ?, ?, ?)");
      Tokenizer toks = new Tokenizer(false);
      int orth;
      int lem;
      while (pages.next()) {
        int doc = pages.getInt(1);
        ins.setInt(1, doc);
        toks.text(pages.getString(2));
        Occ occ;
        while ((occ = toks.word()) != null) {
          if (occ.tag().isPun()) continue;
          if (occ.tag().equals(Tag.NULL)) continue; // inconnu
          if (occ.tag().isName()) lem = -1;
          else lem = lemDic.put(occ.lem(), occ.tag().code());
          orth = orthDic.put(occ.orth(), occ.tag().code(), lem);
          ins.setInt(2, orth);
          ins.setInt(3, occ.tag().code());
          ins.setInt(4, lem);
          ins.setInt(5, occ.start());
          ins.setInt(6, occ.end());
          ins.executeUpdate();
        }
      }
      // System.out.println("Tokenisation "+((System.nanoTime() - start)/1000000.0));
      stmt.execute("INSERT INTO occ" + " (doc, orth, tag, lem, start, end)" + " SELECT doc, orth, tag, lem, start, end"
          + " FROM '" + table + "'");
      // optimized, no pb
      // System.out.println("INSERT in occ "+((System.nanoTime() - start)/1000000.0));
      stmt.execute("DROP TABLE " + table);
      stmt.close();
    }
    catch (SQLException e) {
      System.out.println(Thread.currentThread().getName());
      e.printStackTrace();
    }
    System.out.println("Start index: " + start);
  }

  public static void main(String[] args) throws IOException, SQLException, InterruptedException
  {
    if (args.length < 2) {
      System.out.println(
          "java -Xmx20g -server -cp \"lib/*\" alix.sqlite.ParseBlob textes.sqlite occs.sqlite threads? packSize?");
    }
    String textBase = args[0];
    String indexBase = args[1];
    if (!new File(indexBase).exists()) {
      InputStream in = Presse.class.getResourceAsStream("alix.sqlite");
      Files.copy(in, Paths.get(indexBase));
    }
    int threads = 7;
    if (args.length > 2) threads = Integer.parseInt(args[2]);
    int limit = 1000;
    if (args.length > 3) limit = Integer.parseInt(args[3]);

    int pageCount = ParseBlob.open(textBase, indexBase);

    ExecutorService pool = Executors.newFixedThreadPool(threads);

    long start = System.nanoTime();
    int max = pageCount;
    for (int offset = 0; offset < max; offset += limit) {
      pool.execute(new ParseBlob(offset, offset + limit));
    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    System.out.println((System.nanoTime() - start) / 1000000.0);
    ParseBlob.close();
    System.out.println((System.nanoTime() - start) / 1000000.0);

    // base.unDic.csv(System.out, 300);
  }
}
