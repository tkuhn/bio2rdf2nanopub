package ch.tkuhn.bio2rdf2nanopub;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import net.trustyuri.TrustyUriUtils;

import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.SignNanopub;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.DCTERMS;
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

	@Parameter(names = "-k", description = "Key file")
	private String keyFile = "keys/bio2rdf2nanopub";

	@Parameter(names = "-s", description = "ORCID identifier of person who starts the bot process")
	private String startedByOrcid;

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

	private static final URI provWasGeneratedBy = new URIImpl("http://www.w3.org/ns/prov#wasGeneratedBy");
	private static final URI provWasAssociatedWith = new URIImpl("http://www.w3.org/ns/prov#wasAssociatedWith");
	private static final URI provWasAttributedTo = new URIImpl("http://www.w3.org/ns/prov#wasAttributedTo");
	private static final URI provWasStartedBy = new URIImpl("http://www.w3.org/ns/prov#wasStartedBy");
	private static final URI provSpecializationOf = new URIImpl("http://www.w3.org/ns/prov#specializationOf");
	private static final URI provUsed = new URIImpl("http://www.w3.org/ns/prov#used");
	private static final URI pavVersion = new URIImpl("http://purl.org/pav/version");
	private static final URI dctIdentifier = new URIImpl("http://purl.org/dc/terms/identifier");

	private static Logger logger = LoggerFactory.getLogger(Run.class);

	private static ValueFactory vf = new ValueFactoryImpl();

	private Properties conf;

	private URI codebaseUri, versionUri, instanceUri, processUri;
	private List<URI> versionCreators, instanceCreators;
	private String version;

	private SailRepository sailRepo;
	private String dataset;
	private List<String> nsPrefixes;
	private Map<String,String> namespaces;
	private List<String> nsPrefixesPreload;
	private Map<String,String> namespacesPreload;
	private KeyPair key;
	private UUID uuid;

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
		nsPrefixesPreload = new ArrayList<>();
		namespacesPreload = new HashMap<>();
		addNamespace("prov", "http://www.w3.org/ns/prov#");
		addNamespace("dct", "http://purl.org/dc/terms/");
		addNamespace("pav", "http://purl.org/pav/");
		addNamespace("orcid", "http://orcid.org/");
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
		codebaseUri = makeUri(conf.getProperty("bot-codebase-uri"));
		System.err.println("Codebase URI: " + codebaseUri);
		addNamespace("bot-codebase", codebaseUri.toString());
		version = conf.getProperty("git.commit.id");
		if (!version.matches("[0-9a-f]{40}")) {
			throw new RuntimeException("Invalid git commit identifier: " + version);
		}
		versionUri = makeUri(conf.getProperty("bot-version-uri-prefix") + version);
		System.err.println("Version URI:  " + versionUri);
		addNamespace("bot-version", versionUri.toString());
		versionCreators = new ArrayList<>();
		for (String c : conf.getProperty("bot-version-developer-orcids").split(" ")) {
			versionCreators.add(new URIImpl("http://orcid.org/" + c));
		}
		String publicKeyShort = TrustyUriUtils.getBase64(key.getPublic().getEncoded()).substring(0, 32);
		if (conf.getProperty("bot-instance-uri-prefix") == null) {
			throw new RuntimeException("Property bot-instance-uri-prefix not found: Set it in local.conf.properties");
		}
		instanceUri = makeUri(conf.getProperty("bot-instance-uri-prefix") + publicKeyShort);
		System.err.println("Instance URI: " + instanceUri);
		addNamespace("bot-instance", instanceUri.toString());
		instanceCreators = new ArrayList<>();
		for (String c : conf.getProperty("bot-instance-creator-orcids").split(" ")) {
			instanceCreators.add(new URIImpl("http://orcid.org/" + c));
		}
		uuid = UUID.randomUUID();
		processUri = makeUri(conf.getProperty("bot-instance-uri-prefix") + publicKeyShort + "." + uuid);
		System.err.println("Process URI:  " + processUri);
		addNamespace("bot-process", processUri.toString());
	}

	private void addNamespace(String prefix, String namespace) {
		nsPrefixesPreload.add(prefix);
		namespacesPreload.put(prefix, namespace);
	}

	private URI makeUri(String uriString) {
		try {
			// throw exceptions if URI is not well-formed:
			new URL(uriString).toURI();
		} catch (Exception ex) {
			throw new RuntimeException("Error creating URI: " + uriString, ex);
		}
		return new URIImpl(uriString);
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
		nsPrefixes.addAll(nsPrefixesPreload);
		for (String prefix : namespacesPreload.keySet()) {
			namespaces.put(prefix, namespacesPreload.get(prefix));
		}
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
			Nanopub rawNanopub = new NanopubImpl(sailRepo, (URI) nanopubId, nsPrefixes, namespaces);
			sailRepo.getConnection().clear(rawNanopub.getPubinfoUri());
			addPubinfoStatement(rawNanopub, nanopubId, DCTERMS.CREATED, vf.createLiteral(new Date()));
			addPubinfoStatement(rawNanopub, nanopubId, provWasGeneratedBy, processUri);
			addPubinfoStatement(rawNanopub, processUri, provWasAssociatedWith, instanceUri);
			addPubinfoStatement(rawNanopub, processUri, provUsed, versionUri);
			addPubinfoStatement(rawNanopub, processUri, dctIdentifier, new LiteralImpl(uuid.toString()));
			if (startedByOrcid != null) {
				URI s = new URIImpl("http://orcid.org/" + startedByOrcid);
				addPubinfoStatement(rawNanopub, processUri, provWasStartedBy, s);
			}
			addPubinfoStatement(rawNanopub, instanceUri, provSpecializationOf, codebaseUri);
			for (URI c : instanceCreators) {
				addPubinfoStatement(rawNanopub, instanceUri, provWasAttributedTo, c);
			}
			addPubinfoStatement(rawNanopub, versionUri, DCTERMS.IS_VERSION_OF, codebaseUri);
			addPubinfoStatement(rawNanopub, versionUri, pavVersion, new LiteralImpl(version));
			for (URI c : versionCreators) {
				addPubinfoStatement(rawNanopub, versionUri, provWasAttributedTo, c);
			}
			Nanopub nanopub = new NanopubImpl(sailRepo, (URI) nanopubId, nsPrefixes, namespaces);
			nanopub = SignNanopub.signAndTransform(nanopub, key, instanceUri);
			out.write(NanopubUtils.writeToString(nanopub, RDFFormat.TRIG) + "\n\n");
		}
		result.close();
		out.close();
		logger.info("number of triples: " + sailRepo.getConnection().size());
		logger.info("number of nanopublications: " + count);
	}

	private void addPubinfoStatement(Nanopub rawNanopub, Resource subj, URI pred, Value obj) throws Exception {
		sailRepo.getConnection().add(new StatementImpl(subj, pred, obj), rawNanopub.getPubinfoUri());
	}

	private void clearData() throws RepositoryException {
		sailRepo.getConnection().clear();
		nsPrefixes.clear();
		namespaces.clear();
	}

	private static File getQueryFile(String dataset) {
		return new File(Run.class.getClassLoader().getResource("queries/" + dataset + ".sparql").getFile());
	}

}
