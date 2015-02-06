package ch.tkuhn.bio2rdf2nanopub;

import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.sail.SailRepositoryConnection;
import org.openrdf.sail.SailException;
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
	}

	private static void test() throws SailException, RepositoryException {
		MemoryStore store = new MemoryStore();
		store.initialize();
		SailRepositoryConnection conn = new SailRepository(store).getConnection();
		conn.add(new StatementImpl(
				new URIImpl("http://example.org/s"),
				new URIImpl("http://example.org/p"),
				new URIImpl("http://example.org/o")
			), new URIImpl("http://example.org/g"));
		System.err.println(conn.getStatements(null, null, null, false, new URIImpl("http://example.org/g")).next());
	}

}
