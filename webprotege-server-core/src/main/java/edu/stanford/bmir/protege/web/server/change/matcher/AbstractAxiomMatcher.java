package edu.stanford.bmir.protege.web.server.change.matcher;

import com.google.common.reflect.TypeToken;
import org.semanticweb.owlapi.change.AddAxiomData;
import org.semanticweb.owlapi.change.AxiomChangeData;
import org.semanticweb.owlapi.change.OWLOntologyChangeData;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 16/03/16
 */
public abstract class AbstractAxiomMatcher<A extends OWLAxiom> implements ChangeMatcher {

    private final TypeToken<A> axiomCls;

    public AbstractAxiomMatcher(TypeToken<A> axiomCls) {
        this.axiomCls = axiomCls;
    }

    @Override
    public final Optional<ChangeSummary> getDescription(List<OWLOntologyChangeData> changeData) {
        List<OWLOntologyChangeData> coreChangeData;
        if(changeData.size() != 1) {
            var nonDeclarationChangeData = getNonDeclarationChangeData(changeData);
            if(nonDeclarationChangeData.size() != 1) {
                return Optional.empty();
            }
            else {
                coreChangeData = nonDeclarationChangeData;
            }
        }
        else {
            coreChangeData = changeData;
        }
        var firstChange = coreChangeData.get(0);
        if(!(firstChange instanceof AxiomChangeData)) {
            return Optional.empty();
        }
        OWLAxiom axiom = ((AxiomChangeData) firstChange).getAxiom();
        if(!axiomCls.getRawType().isInstance(axiom)) {
            return Optional.empty();
        }
        if(firstChange instanceof AddAxiomData) {
            return getDescriptionForAddAxiomChange((A) axiom, changeData);
        }
        else {
            return getDescriptionForRemoveAxiomChange((A) axiom);
        }
    }

    private List<OWLOntologyChangeData> getNonDeclarationChangeData(List<OWLOntologyChangeData> changeData) {
        // Inline declarations consist of an entity declaration axiom and zero
        // or more annotations assertions with a subject equal to the IRI of the
        // declared entity
        if(allowSignatureDeclarations()) {
            var subjectProvider = new EntityCreationAxiomSubjectProvider();
            var potentialInlineEntityDeclarationChanges = changeData.stream()
                    .filter(this::isPotentialInlineDeclarationChange)
                    .collect(groupingBy(data -> {
                        var axiom = (OWLAxiom) data.getItem();
                        return subjectProvider.getEntityCreationAxiomSubject(axiom);
                    }));
            var declarationChangeData = potentialInlineEntityDeclarationChanges
                    .values()
                    .stream()
                    .filter(this::containsDeclarationAxiomChange)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
            return changeData.stream()
                    .filter(data -> !declarationChangeData.contains(data))
                    .collect(Collectors.toList());
        }
        else {
            return changeData;
        }
    }

    private boolean containsDeclarationAxiomChange(List<OWLOntologyChangeData> dataList) {
        return dataList
                .stream()
                .anyMatch(data -> data.getItem() instanceof OWLDeclarationAxiom);
    }

    private boolean isPotentialInlineDeclarationChange(OWLOntologyChangeData data) {
        if(data instanceof AddAxiomData) {
            var axiom = ((AddAxiomData) data).getAxiom();
            if(axiom instanceof OWLDeclarationAxiom) {
                return true;
            }
            else return axiom instanceof OWLAnnotationAssertionAxiom;
        }
        else {
            return false;
        }
    }

    protected abstract Optional<ChangeSummary> getDescriptionForAddAxiomChange(A axiom,
                                                                               List<OWLOntologyChangeData> changes);

    protected abstract Optional<ChangeSummary> getDescriptionForRemoveAxiomChange(A axiom);

    protected boolean allowSignatureDeclarations() {
        return false;
    }
}
