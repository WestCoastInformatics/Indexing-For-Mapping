/*
 *    Copyright 2015 West Coast Informatics, LLC
 */
package gov.nih.nlm.ihtsdo.mapping.icd10cm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Converts ICD11 data files into to Lucene indexes.
 * 
 * @goal index-icd11
 */
public class IndexIcd11TextFilesMojo extends AbstractMojo {

  /** The writer. */
  IndexWriter writer = null;

  /**
   * The synonym file
   * 
   * @parameter
   */
  private String synonymFile;

  /**
   * The input file.
   * 
   * @parameter
   */
  private String inputFile;

  /**
   * The output directory, where Lucene indexes will go.
   * 
   * @parameter
   */
  private String indexDir;

  /**
   * Executes the plugin.
   * 
   * @throws MojoExecutionException the mojo execution exception
   */
  @Override
  public void execute() throws MojoExecutionException {

    // Use XML parser to go through extracted XML files
    // and produce HTLM files

    try {
      getLog().info("Starting conversion to lucene ..." + new Date());
      getLog().info("  synonymFile = " + synonymFile);
      getLog().info("  inputFile = " + inputFile);
      getLog().info("  indexDir = " + indexDir);
      // Set up index directories and files
      new File(indexDir).mkdirs();

      String line;
      final BufferedReader syIn =
          new BufferedReader(new FileReader(new File(synonymFile)));
      final Map<String, String> syReplacements = new HashMap<>();
      while ((line = syIn.readLine()) != null) {
        if (line.startsWith("#")) {
          continue;
        }
        final String[] tokens = line.split("\\|");
        syReplacements.put(tokens[0], tokens[1]);
      }
      syIn.close();

      // Open index writer
      final File indexDirFile = new File(indexDir);
      final Directory dir = FSDirectory.open(indexDirFile);
      final Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_43);
      final IndexWriterConfig config =
          new IndexWriterConfig(Version.LUCENE_43, analyzer);
      config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
      writer = new IndexWriter(dir, config);

      final BufferedReader in =
          new BufferedReader(new FileReader(new File(inputFile)));
      while ((line = in.readLine()) != null) {
        if (line.startsWith("#")) {
          continue;
        }
        final String[] tokens = line.split("\\|");
        final String code = tokens[0];
        String text = tokens[1];
        String type = "";
        if (tokens.length > 2) {
          type = tokens[2];
        }
        addDocumentToIndex(code, text, type, writer);
        // do all replacements then check
//        if (text.equals("psychoactive substance dependence")) {
//          System.out.println("breakpoint");
//        }
        boolean found = false;
        String fullReplText = new String(text);
        for (String key : syReplacements.keySet()) {
          if (text.toLowerCase().contains(key)) {
            final String localText =
                text.toLowerCase().replace(key, syReplacements.get(key));
            fullReplText = fullReplText.toLowerCase().replace(key,
                syReplacements.get(key));
            addDocumentToIndex(code, localText, type, writer);
            found = true;
          }
        }
        if (found) {
          addDocumentToIndex(code, fullReplText, type, writer);
        }
      }
      // close Lucene index
      analyzer.close();
      writer.commit();
      writer.close();
      dir.close();
      in.close();
      getLog().info("Finished conversion to lucene ..." + new Date());

    } catch (Exception e) {
      e.printStackTrace();
      throw new MojoExecutionException("Conversion to index failed", e);
    }

  }

  /**
   * Adds the document to index.
   *
   * @param code the code
   * @param text the text
   * @param type the type
   * @param writer writer
   * @throws Exception the exception
   */
  public void addDocumentToIndex(final String code, final String text,
    final String type, final IndexWriter writer) throws Exception {
    final Document document = new Document();
    getLog().debug("code = " + code);
    getLog().debug("text = " + text);
    getLog().debug("type = " + type);
    document.add(new StringField("code", code, Field.Store.YES));
    document.add(new TextField("text", text, Field.Store.YES));
    document.add(new TextField("type", type, Field.Store.YES));
    writer.addDocument(document);
  }

}