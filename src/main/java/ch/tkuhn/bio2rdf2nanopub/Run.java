package ch.tkuhn.bio2rdf2nanopub;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.zip.GZIPOutputStream;

import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.SignNanopub;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.Update;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.sail.SailRepositoryConnection;
import org.openrdf.rio.RDFFormat;
import org.openrdf.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class Run {

	@Parameter(description = "datasets", required = true)
	private List<String> datasets = new ArrayList<String>();

	@Parameter(names = "-k", description = "keyfile")
	private String keyFile = "keys/bio2rdf2nanopub";

	public static final void main(String[] args) {
		Run obj = new Run();
		JCommander jc = new JCommander(obj);
		try {
			jc.parse(args);
		} catch (ParameterException ex) {
			jc.usage();
			System.exit(1);
		}
		obj.run();
		System.exit(0);
	}

	private static Logger logger = LoggerFactory.getLogger(Run.class);

	private Properties conf;

	private SailRepository sailRepo;
	private String dataset;
	private List<String> nsPrefixes;
	private Map<String,String> namespaces;
	private KeyPair key;

	public void run() {
		try {
			init();
			for (String dataset : datasets) {
				this.dataset = dataset;
				loadData(dataset);
				writeData();
				clearData();
			}
		} catch (Throwable th) {
			logger.error(th.getMessage(), th);
			System.exit(1);
		}
		logger.info("success");
	}

	private void init() throws Exception {
		MemoryStore store = new MemoryStore();
		store.initialize();
		sailRepo = new SailRepository(store);
		nsPrefixes = new ArrayList<>();
		namespaces = new HashMap<>();
		try {
			key = SignNanopub.loadKey(keyFile);
		} catch (FileNotFoundException ex) {
			logger.error("No key pair found. Specify one with '-k' or " +
				"run 'path/to/nanopub-java/scripts/MakeKeys.sh -f keys/bio2rdf2nanopub' to generate one.");
			throw ex;
		}
		conf = new Properties();
		InputStream in1 = null;
		InputStream in2 = null;
		try {
			in1 = Run.class.getResourceAsStream("conf.properties");
			try {
				conf.load(in1);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
			in2 = Run.class.getResourceAsStream("local.conf.properties");
			if (in2 != null) {
				try {
					conf.load(in2);
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
		} finally {
			close(in1);
			close(in2);
		}
		//System.err.println("version: " + conf.getProperty("version"));
		//System.err.println("build date: " + conf.getProperty("build-date"));
	}

	private void close(InputStream st) {
		if (st == null) return;
		try {
			st.close();
		} catch (IOException ex) {
			logger.error(ex.getMessage(), ex);
		}
	}

	private static final String prefixPattern = "^\\s*(prefix|PREFIX)\\s+([a-zA-Z\\-_]+)\\s*:\\s*<(.*)>\\s*$";

	private void loadData(String dataset) throws Exception {
		SailRepositoryConnection conn = sailRepo.getConnection();
		Scanner scanner = new Scanner(getQueryFile(dataset));
		String query = "";
		while (scanner.hasNextLine()) {
			String line = scanner.nextLine();
			query += line + "\n";
			if (line.matches(prefixPattern)) {
				String prefix = line.replaceFirst(prefixPattern, "$2");
				String ns = line.replaceFirst(prefixPattern, "$3");
				nsPrefixes.add(prefix);
				namespaces.put(prefix, ns);
			}
		}
		scanner.close();
		Update update = conn.prepareUpdate(QueryLanguage.SPARQL, query);
		update.execute();
	}

	private void writeData() throws Exception {
		SailRepositoryConnection conn = sailRepo.getConnection();
		int count = 0;
		RepositoryResult<Resource> result = conn.getContextIDs();
		File outputFile = new File("nanopubs/" + dataset + ".trig.gz");
		if (!outputFile.getParentFile().isDirectory()) {
			outputFile.getParentFile().mkdir();
		}
		OutputStreamWriter out = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(outputFile)));
		while (result.hasNext()) {
			Resource graph = result.next();
			RepositoryResult<Statement> r = conn.getStatements(null, RDF.TYPE, Nanopub.NANOPUB_TYPE_URI, false, graph);
			if (!r.hasNext()) continue;
			Resource nanopubId = r.next().getSubject();
			if (!(nanopubId instanceof URI)) {
				logger.error("Nanopub ID has to be a URI: " + nanopubId);
				continue;
			}
			count++;
			Nanopub nanopub = new NanopubImpl(sailRepo, (URI) nanopubId, nsPrefixes, namespaces);
			nanopub = SignNanopub.signAndTransform(nanopub, key);
			out.write(NanopubUtils.writeToString(nanopub, RDFFormat.TRIG) + "\n\n");
		}
		result.close();
		out.close();
		logger.info("number of triples: " + sailRepo.getConnection().size());
		logger.info("number of nanopublications: " + count);
	}

	private void clearData() throws RepositoryException {
		sailRepo.getConnection().clear();
	}

	private static File getQueryFile(String dataset) {
		return new File(Run.class.getClassLoader().getResource("queries/" + dataset + ".sparql").getFile());
	}

}
