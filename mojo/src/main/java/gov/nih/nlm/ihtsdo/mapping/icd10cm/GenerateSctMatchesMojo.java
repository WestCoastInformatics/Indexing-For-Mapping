package gov.nih.nlm.ihtsdo.mapping.icd10cm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Matches SNOMED against the lucene index gereated by the other mojo and makes
 * output.
 * 
 * @goal lookup
 */
public class GenerateSctMatchesMojo extends AbstractMojo {

  /**
   * The input SCT file.
   * 
   * @parameter
   */
  private String inputFile;

  /**
   * The index dir.
   * 
   * @parameter
   */
  private String indexDir;

  /**
   * The output file.
   * 
   * @parameter
   */
  private String outputFile;

  /**
   * The score threshold.
   * 
   * @parameter
   */
  private long scoreThreshold;

  /**
   * Executes the plugin.
   * 
   * @throws MojoExecutionException the mojo execution exception
   */
  @SuppressWarnings("resource")
  @Override
  public void execute() throws MojoExecutionException {

    // Use XML parser to go through extracted XML files
    // and produce HTLM files
    try {
      getLog().info("Starting SCT matching ..." + new Date());
      getLog().info("  inputFile = " + inputFile);
      getLog().info("  indexDir = " + indexDir);
      getLog().info("  outputFile = " + outputFile);
      getLog().info("  scoreThreshold = " + scoreThreshold);

      IndexReader indexReader = null;
      IndexSearcher indexSearcher = null;
      Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_43);
      File indexDirFile = new File(indexDir);
      Directory dir = FSDirectory.open(indexDirFile);
      indexReader = DirectoryReader.open(dir);
      indexSearcher = new IndexSearcher(indexReader);

      BufferedReader in =
          new BufferedReader(new FileReader(new File(inputFile)));
      new File(outputFile).getParentFile().mkdirs();
      PrintWriter out = new PrintWriter(new FileWriter(new File(outputFile)));

      String prevSctid = "";
      Map<String, Float> codeScoreMap = new HashMap<>();
      String line;
      while ((line = in.readLine()) != null) {
        String[] tokens = line.split("\\|");
        String sctid = tokens[0];
        String description = tokens[1];
        // Remove boolean expressions
        description = description.replaceAll(" AND ", "");
        description = description.replaceAll(" and ", "");
        description = description.replaceAll(" OR ", "");
        description = description.replaceAll(" or ", "");
        description = description.replaceAll(" NOT ", "");
        description = description.replaceAll(" not ", "");
        description = description.replaceAll(" AND/OR ", "");
        description = description.replaceAll(" and/or ", "");
        description = description.replaceAll("[-})({?+^/:*\"']", " ");
        description = description.replaceAll("\\[", " ");
        description = description.replaceAll("\\]", " ");
        if (description.endsWith(" OR")) {
          description = description.replaceAll(" OR", "");
        }

        if (!sctid.equals(prevSctid)) {
          if (sctid.compareTo(prevSctid) < 0) {
            throw new Exception("desc.txt is not in order");
          }
          getLog().info("sctid = " + prevSctid + ", " + codeScoreMap);
          for (String key : codeScoreMap.keySet()) {
            float score = codeScoreMap.get(key);
            if (score > scoreThreshold) {
              out.println(prevSctid + "|" + key + "|" + score);
            }
          }
          codeScoreMap = new HashMap<>();
        }
        prevSctid = sctid;

        // System.out.println("desc = " + description);
        Query query =
            new QueryParser(Version.LUCENE_43, "text", analyzer)
                .parse(description);

        // System.out.println("sctid = " + sctid);
        // System.out.println("  query = " + query.toString());
        int numResults = 20;
        ScoreDoc[] hits = indexSearcher.search(query, numResults).scoreDocs;
        for (int i = 0; i < hits.length; i++) {
          Document doc = indexSearcher.doc(hits[i].doc);
          IndexableField code = doc.getField("code");
          String result = code.stringValue();
          if (codeScoreMap.containsKey(result)) {
            if (codeScoreMap.get(result) < hits[i].score) {
              codeScoreMap.put(result, hits[i].score);
            }
          } else {
            if (hits[i].score > scoreThreshold) {
              codeScoreMap.put(result, hits[i].score);
            }
          }
        }
      }

      analyzer.close();
      in.close();
      dir.close();
      out.close();

      getLog().info("Finished SCT matching ..." + new Date());

    } catch (Exception e) {
      e.printStackTrace();
      throw new MojoExecutionException("Generation of SCT matches failed", e);
    }

  }

}