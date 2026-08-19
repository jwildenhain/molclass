package molclass.predictor;

import java.sql.Connection;
import java.util.BitSet;
import java.util.OptionalLong;

import molclass.models.MurckoScaffoldCore;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Spring-facing wrapper around {@link MurckoScaffoldCore} -- the actual CDK/SQL logic lives
 * there so it is shared with {@code molclass.models.V3ModelRebuilder}'s training-time
 * scaffold-stratified splitting rather than duplicated (and risking the two copies drifting
 * out of sync on subtle CDK ordering fixes, as already happened once with the
 * Kekulize-before-Aromaticity bug this class's javadoc used to describe).
 */
@Service
public class MurckoScaffoldService {

    @Value("${molclass.v3.schema:molclass_v3}")
    private String schema;

    private MurckoScaffoldCore core;

    private MurckoScaffoldCore core() {
        if (core == null) core = new MurckoScaffoldCore(schema);
        return core;
    }

    /**
     * Returns the molecule's stored Murcko scaffold id, computing and storing it first if
     * necessary. Empty if the molecule has no ring system (Bemis-Murcko frameworks are
     * undefined for acyclic structures) or could not be parsed.
     */
    public OptionalLong ensureScaffold(Connection connection, long moleculeId) throws Exception {
        return core().ensureScaffold(connection, moleculeId);
    }

    /** The Bemis-Murcko framework SMILES for an arbitrary structure, not tied to a stored molecule. */
    public String frameworkSmilesFor(IAtomContainer molecule) throws Exception {
        return core().frameworkSmilesFor(molecule);
    }

    /** A structural fingerprint of a Murcko framework, used for scaffold-space similarity. */
    public BitSet frameworkFingerprint(String scaffoldSmiles) throws Exception {
        return core().frameworkFingerprint(scaffoldSmiles);
    }
}
