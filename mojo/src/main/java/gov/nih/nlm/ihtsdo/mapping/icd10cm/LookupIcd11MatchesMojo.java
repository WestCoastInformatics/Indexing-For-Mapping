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
 * Matches SNOMED against the lucene index generated from icd11 data.
 * 
 * 
 * @goal lookup-icd11
 */
public class LookupIcd11MatchesMojo extends AbstractMojo {

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
      getLog().info("Starting lookup ..." + new Date());
      getLog().info("  inputFile = " + inputFile);
      getLog().info("  indexDir = " + indexDir);
      getLog().info("  outputFile = " + outputFile);
      getLog().info("  scoreThreshold = " + scoreThreshold);

      final Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_43);
      final File indexDirFile = new File(indexDir);
      final Directory dir = FSDirectory.open(indexDirFile);
      final IndexReader indexReader = DirectoryReader.open(dir);
      final IndexSearcher indexSearcher = new IndexSearcher(indexReader);

      final BufferedReader in =
          new BufferedReader(new FileReader(new File(inputFile)));
      new File(outputFile).getParentFile().mkdirs();
      final PrintWriter out =
          new PrintWriter(new FileWriter(new File(outputFile)));

      String prevSctid = "";
      Map<String, Float> codeScoreMap = new HashMap<>();
      Map<String, String> codeTextMap = new HashMap<>();
      Map<String, String> codeDescMap = new HashMap<>();
      String line;
      while ((line = in.readLine()) != null) {
        final String[] tokens = line.split("\\|");
        final String sctid = tokens[0];
        String description = tokens[1];
        final String descType = tokens[2];

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
              out.println(prevSctid + "|" + key + "|" + score + "|"
                  + codeDescMap.get(key) + "|" + codeTextMap.get(key));
            }
          }
          codeScoreMap = new HashMap<>();
          codeTextMap = new HashMap<>();
          codeDescMap = new HashMap<>();
        }
        prevSctid = sctid;

        // System.out.println("desc = " + description);
        final Query query = new QueryParser(Version.LUCENE_43, "text", analyzer)
            .parse(description);

        // System.out.println("sctid = " + sctid);
        // System.out.println(" query = " + query.toString());
        int numResults = 20;
        final ScoreDoc[] hits =
            indexSearcher.search(query, numResults).scoreDocs;
        for (int i = 0; i < hits.length; i++) {
          final Document doc = indexSearcher.doc(hits[i].doc);
          final IndexableField code = doc.getField("code");
          final IndexableField text = doc.getField("text");
          final IndexableField type = doc.getField("type");
          final String result = code.stringValue();

          // Bump exact matches and "preferred" matches
          Float score = hits[i].score;
          if (text.stringValue().toLowerCase()
              .equals(description.toLowerCase())) {
            score = 10.0f;
          }
          if (descType.equals("1")) {
            score *= 1.25f;
          }
          // weight by similar numbers of words.
          final int descWords = description.split(" ").length;
          final int textWords = text.stringValue().split(" ").length;
          final int ctDiff = Math.abs(descWords - textWords);

          // Penalize difference in words unless it is an "other" case
          if (ctDiff > 1 && !type.stringValue().equals("other")) {
            score *= (1.5f / ctDiff);
          }

          // if "unspecified", penalize difference in words more
          if (type.stringValue().equals("unspecified")
              || type.stringValue().equals("NOS")) {
            if (descWords - textWords > 0) {
              score = score * .75f;
            }
          }

          if (codeScoreMap.containsKey(result)) {
            if (codeScoreMap.get(result) < score) {
              codeScoreMap.put(result, score);
              codeTextMap.put(result, text.stringValue());
              codeDescMap.put(result, description);
            }
          } else {
            if (score > scoreThreshold) {
              codeScoreMap.put(result, score);
              codeTextMap.put(result, text.stringValue());
              codeDescMap.put(result, description);
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