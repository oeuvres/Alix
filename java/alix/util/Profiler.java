/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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
package alix.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.instrument.Instrumentation;

public class Profiler
{
  private static Instrumentation instrumentation;

  public static void premain(String args, Instrumentation inst)
  {
    instrumentation = inst;
  }

  public static long getObjectSize(Object o)
  {
    return instrumentation.getObjectSize(o);
  }

  /**
   * Works only with Serializable object
   * 
   * @param o
   * @return
   * @throws IOException
   */
  public static long serSize(Object o) throws IOException
  {
    Serializable ser = null;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(ser);
    oos.close();
    return baos.size();
  }
}
