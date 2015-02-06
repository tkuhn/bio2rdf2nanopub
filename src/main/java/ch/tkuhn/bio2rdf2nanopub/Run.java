package ch.tkuhn.bio2rdf2nanopub;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.trusty.MakeTrustyNanopub;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.Update;
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

	private SailRepository sailRepo;

	public void run() {
		try {
			init();
			for (String dataset : datasets) {
				test(dataset);
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
	}

	private void test(String dataset) throws Exception {
		SailRepositoryConnection conn = sailRepo.getConnection();
		Scanner scanner = new Scanner(getQueryFile(dataset));
		String query = "";
		while (scanner.hasNextLine()) {
			query += scanner.nextLine() + "\n";
		}
		scanner.close();
		Update update = conn.prepareUpdate(QueryLanguage.SPARQL, query);
		update.execute();
		Nanopub nanopub = new NanopubImpl(sailRepo, new URIImpl("http://bio2rdf.org/drugbank_resource:DB00247_BE0000650_nanopub"));
		nanopub = MakeTrustyNanopub.transform(nanopub);
		logger.info(NanopubUtils.writeToString(nanopub, RDFFormat.TRIG));
		logger.info("size: " + sailRepo.getConnection().size());
		sailRepo.getConnection().clear();
	}

	private static File getQueryFile(String dataset) {
		return new File(Run.class.getClassLoader().getResource("queries/" + dataset + ".sparql").getFile());
	}

}
