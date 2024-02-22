package hu.bme.mit.theta.frontend.petrinet.pnml;

import fr.lip6.move.pnml.framework.hlapi.HLAPIRootClass;
import fr.lip6.move.pnml.framework.utils.PNMLUtils;
import fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI;
import hu.bme.mit.theta.frontend.petrinet.model.PetriNet;

import java.io.File;
import java.util.List;
import java.util.Objects;

public final class PetriNetParser {
    public static enum PetriNetType {
        PTNet,
        Other
    }

    private final HLAPIRootClass root;

    public static PetriNetParser loadPnml(File file) throws Exception {
        final HLAPIRootClass root = PNMLUtils.importPnmlDocument(file, false);
        return new PetriNetParser(root);
    }

    private PetriNetParser(final HLAPIRootClass root) {
        this.root = Objects.requireNonNull(root);
    }

    public PetriNetType getPetriNetType() {
        if (root instanceof PetriNetDocHLAPI) {
            return PetriNetType.PTNet;
        }
        return PetriNetType.Other;
    }

    public List<PetriNet> parsePTNet() throws PnmlParseException {
        if (root instanceof PetriNetDocHLAPI) {
            return new Lip6PnmlToPetrinet((PetriNetDocHLAPI) root).parse();
        }
        throw new PnmlParseException("The file was not a P/T Net.");
    }
}
