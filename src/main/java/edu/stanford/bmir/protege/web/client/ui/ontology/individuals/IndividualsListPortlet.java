package edu.stanford.bmir.protege.web.client.ui.ontology.individuals;

import com.google.common.base.Optional;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtext.client.core.EventObject;
import com.gwtext.client.widgets.Button;
import com.gwtext.client.widgets.Toolbar;
import com.gwtext.client.widgets.ToolbarButton;
import com.gwtext.client.widgets.event.ButtonListenerAdapter;
import com.gwtext.client.widgets.form.Field;
import com.gwtext.client.widgets.form.TextField;
import com.gwtext.client.widgets.form.event.TextFieldListenerAdapter;
import com.gwtext.client.widgets.layout.FitLayout;
import edu.stanford.bmir.protege.web.client.LoggedInUserProvider;
import edu.stanford.bmir.protege.web.client.action.CreateHandler;
import edu.stanford.bmir.protege.web.client.action.DeleteHandler;
import edu.stanford.bmir.protege.web.client.action.NullCreateHandler;
import edu.stanford.bmir.protege.web.client.action.NullDeleteHandler;
import edu.stanford.bmir.protege.web.client.dispatch.DispatchServiceManager;
import edu.stanford.bmir.protege.web.client.entitieslist.EntitiesList;
import edu.stanford.bmir.protege.web.client.entitieslist.EntitiesListImpl;
import edu.stanford.bmir.protege.web.client.individualslist.IndividualsListView;
import edu.stanford.bmir.protege.web.client.individualslist.IndividualsListViewPresenter;
import edu.stanford.bmir.protege.web.client.permissions.LoggedInUserProjectPermissionChecker;
import edu.stanford.bmir.protege.web.client.permissions.PermissionChecker;
import edu.stanford.bmir.protege.web.client.project.Project;
import edu.stanford.bmir.protege.web.client.rpc.data.EntityData;
import edu.stanford.bmir.protege.web.client.rpc.data.ValueType;
import edu.stanford.bmir.protege.web.client.rpc.data.layout.PortletConfiguration;
import edu.stanford.bmir.protege.web.client.ui.portlet.AbstractOWLEntityPortlet;
import edu.stanford.bmir.protege.web.shared.DataFactory;
import edu.stanford.bmir.protege.web.shared.entity.OWLEntityData;
import edu.stanford.bmir.protege.web.shared.entity.OWLNamedIndividualData;
import edu.stanford.bmir.protege.web.shared.project.ProjectId;
import edu.stanford.bmir.protege.web.shared.selection.SelectionModel;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLNamedIndividual;

import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Portlet for showing a list of individuals. The list is filled with the
 * instances of the class given as argument to <code>setEntity</code> method.
 * Normally, it is used together with the class tree portlet. The portlet can
 * also be configured in the configuration file to show always only the
 * instances of a certain class by setting a property of the portlet
 * <code>showOnlyClass</code> to point to a class.
 *
 * @author Tania Tudorache <tudorache@stanford.edu>
 */
public class IndividualsListPortlet extends AbstractOWLEntityPortlet implements IndividualsListView {

    private static final String PRECONFIGURED_CLASS = "showOnlyClass";


    private DeleteHandler deleteHandler;

    private CreateHandler createHandler;


    private IndividualsListViewPresenter presenter;

    /*
     * Retrieved from the project configuration. If it is set,
     * then the individuals list will always display the instances
     * of the preconfigured class.
     */
    protected Optional<OWLClass> preconfiguredClass = Optional.absent();

    private ToolbarButton createButton;

    private ToolbarButton deleteButton;

    private EntitiesList<OWLNamedIndividualData> individualsList;

    private final DispatchServiceManager dispatchServiceManager;

    private final LoggedInUserProvider loggedInUserProvider;

    private final LoggedInUserProjectPermissionChecker permissionChecker;

    @Inject
    public IndividualsListPortlet(SelectionModel selectionModel,
                                  EventBus eventBus,
                                  DispatchServiceManager dispatchServiceManager,
                                  LoggedInUserProvider loggedInUserProvider,
                                  ProjectId projectId,
                                  LoggedInUserProjectPermissionChecker permissionChecker) {
        super(selectionModel, eventBus, projectId, loggedInUserProvider);
        this.dispatchServiceManager = dispatchServiceManager;
        this.loggedInUserProvider = loggedInUserProvider;
        this.permissionChecker = permissionChecker;
        setLayout(new FitLayout());
        setTitle("Individuals");
        individualsList = new EntitiesListImpl<OWLNamedIndividualData>();
        add(individualsList.asWidget());
        individualsList.addSelectionHandler(new SelectionHandler<OWLNamedIndividualData>() {
            @Override
            public void onSelection(com.google.gwt.event.logical.shared.SelectionEvent<OWLNamedIndividualData> event) {
                getSelectionModel().setSelection(event.getSelectedItem());
            }
        });
        addToolbarButtons();
        initConfiguration();
        if (preconfiguredClass != null && preconfiguredClass.isPresent()) {
            presenter.setType(preconfiguredClass.get());
        }
        deleteHandler = new NullDeleteHandler();
        createHandler = new NullCreateHandler();
        presenter = new IndividualsListViewPresenter(getProjectId(), this, dispatchServiceManager);
    }


    @Override
    public void setPortletConfiguration(PortletConfiguration portletConfiguration) {
        super.setPortletConfiguration(portletConfiguration);
        initConfiguration();
        if (preconfiguredClass.isPresent()) {
            presenter.setType(preconfiguredClass.get());
        }
    }

    private void initConfiguration() {
        PortletConfiguration config = getPortletConfiguration();
        if (config == null) {
            return;
        }
        Map<String, Object> properties = config.getProperties();
        if (properties == null) {
            return;
        }
        final String preconfiguredClassName = (String) properties.get(PRECONFIGURED_CLASS);
        if (preconfiguredClassName != null) {
            preconfiguredClass = Optional.of(DataFactory.getOWLClass(preconfiguredClassName));
        }
        else {
            preconfiguredClass = Optional.absent();
        }
    }

    @Override
    protected void handleBeforeSetEntity(Optional<OWLEntityData> existingEntity) {
        super.handleBeforeSetEntity(existingEntity);
    }

    @Override
    protected void handleAfterSetEntity(Optional<OWLEntityData> entityData) {
        Optional<OWLClass> selectedClass;
        if(preconfiguredClass.isPresent()) {
            selectedClass = preconfiguredClass;
        }
        else if(getSelectionModel().getLastSelectedClassData().isPresent()) {
            selectedClass = Optional.of(getSelectionModel().getLastSelectedClassData().get().getEntity());
        }
        else {
            selectedClass = Optional.absent();
        }


        if(selectedClass.isPresent()) {
            presenter.setType(selectedClass.get());
            updateTitle(entityData);
        }
        else {
            presenter.clearType();
        }
    }

    private void updateTitle(Optional<OWLEntityData> entityData) {
        if(entityData.isPresent()) {
            setTitle("Individuals for " + entityData.get().getBrowserText());
        }
        else {
            setTitle("Individuals (nothing selected)");
        }
    }

    protected void addToolbarButtons() {
        setTopToolbar(new Toolbar());
        Toolbar toolbar = getTopToolbar();
        createButton = new ToolbarButton("Create");
        createButton.setCls("toolbar-button");
        createButton.addListener(new ButtonListenerAdapter() {
            @Override
            public void onClick(Button button, EventObject e) {
                createHandler.handleCreate();
            }
        });
        createButton.setDisabled(!permissionChecker.hasWritePermission());
        toolbar.addButton(createButton);
        deleteButton = new ToolbarButton("Delete");
        deleteButton.setCls("toolbar-button");
        deleteButton.addListener(new ButtonListenerAdapter() {
            @Override
            public void onClick(Button button, EventObject e) {
                getDeleteHandler().handleDelete();
            }
        });
        deleteButton.setDisabled(!permissionChecker.hasWritePermission());
        toolbar.addButton(deleteButton);

        Widget searchField = createSearchField();
        if (searchField != null) {
            toolbar.addFill();
            toolbar.addSeparator();
            toolbar.addText("&nbsp<i>Search</i>:&nbsp&nbsp");
            toolbar.addElement(searchField.getElement());
        }
    }

    protected Widget createSearchField() {
        final TextField searchField = new TextField("Search: ", "search");
        searchField.setAutoWidth(true);
        searchField.setEmptyText("Type search string");
        searchField.addListener(new TextFieldListenerAdapter() {
            @Override
            public void onSpecialKey(Field field, EventObject e) {
                if (e.getKey() == EventObject.ENTER) {
                    // TODO: SELECTION
//                    SearchUtil su = new SearchUtil(getProjectId(), IndividualsListPortlet.this);
//                    su.setBusyComponent(getTopToolbar());
//                    su.setSearchedValueType(ValueType.Instance);
//                    su.search(searchField.getText());
                }
            }
        });
        return searchField;
    }

    public List<EntityData> getSelection() {
        Optional<OWLNamedIndividualData> selection = individualsList.getSelectedEntity();
        if(selection.isPresent()) {
            List<EntityData> result = new ArrayList<EntityData>();
            OWLNamedIndividual entity = selection.get().getEntity();
            EntityData entityData = new EntityData(entity.getIRI().toString(), selection.get().getBrowserText());
            entityData.setValueType(ValueType.Instance);
            result.add(entityData);
            return result;
        }
        else {
            return Collections.emptyList();
        }
    }

    @Override
    public void onPermissionsChanged() {
        updateButtonStates();
    }

    public void updateButtonStates() {
        if (permissionChecker.hasWritePermission()) {
            createButton.enable();
            deleteButton.enable();
        } else {
            createButton.disable();
            deleteButton.disable();
        }
    }

    @Override
    public void setListData(List<OWLNamedIndividualData> individuals) {
        individualsList.setListData(individuals);
    }

    @Override
    public void addListData(Collection<OWLNamedIndividualData> individuals) {
        individualsList.addAll(individuals);
    }

    @Override
    public void removeListData(Collection<OWLNamedIndividualData> individuals) {
        individualsList.removeAll(individuals);
    }

    @Override
    public Collection<OWLNamedIndividualData> getSelectedIndividuals() {
        return individualsList.getSelectedEntity().asSet();
    }

    @Override
    public Optional<OWLNamedIndividualData> getSelectedIndividual() {
        return individualsList.getSelectedEntity();
    }

    @Override
    public void setSelectedIndividual(OWLNamedIndividualData individual) {
        individualsList.setSelectedEntity(individual);
    }

    @Override
    public void setCreateHandler(CreateHandler createHandler) {
        this.createHandler = checkNotNull(createHandler);
    }

    @Override
    public void setDeleteHandler(DeleteHandler handler) {
        this.deleteHandler = checkNotNull(handler);
    }

    public DeleteHandler getDeleteHandler() {
        return deleteHandler;
    }

    @Override
    public HandlerRegistration addSelectionHandler(SelectionHandler<OWLNamedIndividualData> handler) {
        return individualsList.addSelectionHandler(handler);
    }
}
