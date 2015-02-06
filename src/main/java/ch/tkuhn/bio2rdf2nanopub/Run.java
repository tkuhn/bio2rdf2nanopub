package ch.tkuhn.bio2rdf2nanopub;

import java.io.File;
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

public class Run {

	public static void main(String[] args) {
		try {
			test();
		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		System.out.println("success");
		System.exit(0);
	}

	private static void test() throws Exception {
		MemoryStore store = new MemoryStore();
		store.initialize();
		SailRepository sailRepo = new SailRepository(store);
		SailRepositoryConnection conn = sailRepo.getConnection();
		File file = new File(Run.class.getClassLoader().getResource("drugbank.sparql").getFile());
		Scanner scanner = new Scanner(file);
		String query = "";
		while (scanner.hasNextLine()) {
			query += scanner.nextLine() + "\n";
		}
		scanner.close();
		Update update = conn.prepareUpdate(QueryLanguage.SPARQL, query);
		update.execute();
		Nanopub nanopub = new NanopubImpl(sailRepo, new URIImpl("http://bio2rdf.org/drugbank_resource:DB00247_BE0000650_nanopub"));
		nanopub = MakeTrustyNanopub.transform(nanopub);
		System.err.println(NanopubUtils.writeToString(nanopub, RDFFormat.TRIG));
		System.err.println(sailRepo.getConnection().size());
	}

}
