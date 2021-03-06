package edu.stanford.bmir.protege.web.server.revision;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import edu.stanford.bmir.protege.web.server.inject.project.ChangeHistoryFile;
import edu.stanford.bmir.protege.web.shared.HasDispose;
import edu.stanford.bmir.protege.web.shared.project.ProjectId;
import edu.stanford.bmir.protege.web.shared.revision.RevisionNumber;
import edu.stanford.bmir.protege.web.shared.user.UserId;
import org.semanticweb.binaryowl.BinaryOWLOntologyChangeLog;
import org.semanticweb.binaryowl.change.OntologyChangeRecordList;
import org.semanticweb.binaryowl.chunk.SkipSetting;
import org.semanticweb.owlapi.change.*;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static edu.stanford.bmir.protege.web.server.revision.RevisionSerializationVocabulary.*;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 29/05/15
 */
public class RevisionStoreImpl implements RevisionStore, HasDispose {

    private static final Logger logger = LoggerFactory.getLogger(RevisionStoreImpl.class);

    private final ExecutorService changeSerializationExecutor;

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    private final Lock readLock = readWriteLock.readLock();

    private final Lock writeLock = readWriteLock.writeLock();

    private final ProjectId projectId;

    private final OWLDataFactory dataFactory;

    private final File changeHistoryFile;

    private ImmutableList<Revision> revisions = ImmutableList.of();


    @Inject
    public RevisionStoreImpl(@Nonnull ProjectId projectId,
                             @Nonnull @ChangeHistoryFile File changeHistoryFile,
                             @Nonnull OWLDataFactory dataFactory) {
        this.projectId = checkNotNull(projectId);
        this.dataFactory = checkNotNull(dataFactory);
        this.changeHistoryFile = checkNotNull(changeHistoryFile);
        changeSerializationExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName(thread.getName().replace("thread", "change-serializer-thread"));
            return thread;
        });
    }

    @Nonnull
    @Override
    public Optional<Revision> getRevision(@Nonnull RevisionNumber revisionNumber) {
        if(revisions.isEmpty()) {
            return Optional.empty();
        }
        int index = getRevisionIndexForRevision(revisionNumber);
        if(index < 0 || revisions.size() <= index) {
            return Optional.empty();
        }
        else {
            return Optional.of(revisions.get(index));
        }
    }

    private int getRevisionIndexForRevision(RevisionNumber revision) {
        try {
            readLock.lock();
            if(revisions.isEmpty()) {
                return -1;
            }
            if(revision.isHead()) {
                return revisions.size() - 1;
            }
            var firstRevision = revisions.get(0);
            if(revision.compareTo(firstRevision.getRevisionNumber()) < 0) {
                return -1;
            }
            var lastRevision = revisions.get(revisions.size() - 1);
            if(lastRevision.getRevisionNumber() == revision) {
                return revisions.size() - 1;
            }
            var dummyRevision = Revision.createEmptyRevisionWithRevisionNumber(revision);
            return Collections.binarySearch(revisions, dummyRevision);
        } finally {
            readLock.unlock();
        }
    }

    @Nonnull
    @Override
    public ImmutableList<Revision> getRevisions() {
        try {
            readLock.lock();
            return revisions;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void addRevision(@Nonnull Revision revision) {
        checkNotNull(revision);
        try {
            writeLock.lock();
            if(revision.getRevisionNumber().compareTo(getCurrentRevisionNumber()) <= 0) {
                throw new IllegalArgumentException(String.format("Revision number (%d) must be greater than the current revision number (%d)", revision
                        .getRevisionNumber()
                        .getValue(), getCurrentRevisionNumber().getValue()));
            }
            var extendedListBuilder = ImmutableList.<Revision>builder();
            extendedListBuilder.addAll(revisions);
            extendedListBuilder.add(revision);
            revisions = extendedListBuilder.build();
            persistChanges(revision);
        } finally {
            writeLock.unlock();
        }

    }

    @Nonnull
    @Override
    public RevisionNumber getCurrentRevisionNumber() {
        try {
            readLock.lock();
            if(revisions.isEmpty()) {
                return RevisionNumber.getRevisionNumber(0);
            }
            return revisions.get(revisions.size() - 1).getRevisionNumber();
        } finally {
            readLock.unlock();
        }

    }

    private void persistChanges(Revision revision) {
        try {
            writeLock.lock();
            var revisionSerializationTask = new RevisionSerializationTask(changeHistoryFile, revision);
            if(revisions.size() != 1) {
                changeSerializationExecutor.submit(revisionSerializationTask);
            }
            else {
                // Save immediately
                try {
                    logger.info("{} Saving first revision of project", projectId);
                    revisionSerializationTask.call();
                } catch(IOException e) {
                    logger.error("{} An error occurred whilst saving the first revision of the project.  Cause: {}.", projectId, e
                            .getMessage(), e);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void load() {
        try {
            writeLock.lock();
            if(!changeHistoryFile.exists()) {
                changeHistoryFile.getParentFile().mkdirs();
                return;
            }
            var revisionsBuilder = ImmutableList.<Revision>builder();
            var axiomInterner = Interners.<OWLAxiom>newStrongInterner();
            var metadataInterner = Interners.<String>newStrongInterner();
            var userIdInterner = Interners.<UserId>newStrongInterner();

            try {
                logger.info("{} Loading change history", projectId);
                var stopwatch = Stopwatch.createStarted();
                var changeLog = new BinaryOWLOntologyChangeLog();
                var inputStream = new BufferedInputStream(new FileInputStream(changeHistoryFile));
                changeLog.readChanges(inputStream, dataFactory, (changeRecordList, skipSetting, l) -> {
                    var metadata = changeRecordList.getMetadata();
                    var userName = metadataInterner.intern(metadata.getStringAttribute(USERNAME_METADATA_ATTRIBUTE.getVocabularyName(), ""));
                    var revisionNumberValue = metadata.getLongAttribute(REVISION_META_DATA_ATTRIBUTE.getVocabularyName(), 0L);
                    var revisionNumber = RevisionNumber.getRevisionNumber(revisionNumberValue);
                    var description = metadata.getStringAttribute(DESCRIPTION_META_DATA_ATTRIBUTE.getVocabularyName(), "");
                    var userId = userIdInterner.intern(UserId.getUserId(userName));

                    var internedChangeRecords = internChangeRecords(changeRecordList, axiomInterner);
                    var revision = new Revision(userId, revisionNumber, internedChangeRecords, changeRecordList.getTimestamp(), description);
                    revisionsBuilder.add(revision);
                }, SkipSetting.SKIP_NONE);
                inputStream.close();
                stopwatch.stop();
                revisions = revisionsBuilder.build();
                logger.info("{} Change history loading complete.  Loaded {} revisions in {} ms.", projectId, revisions.size(), stopwatch
                        .elapsed(TimeUnit.MILLISECONDS));

            } catch(Exception e) {
                logger.error("{} Failed to load change history for project.  Cause: {}", projectId, e.getMessage(), e);
            }
        } finally {
            writeLock.unlock();
        }


    }

    @Override
    public void dispose() {
        changeSerializationExecutor.shutdown();
    }

    @Nonnull
    private static ImmutableList<OWLOntologyChangeRecord> internChangeRecords(OntologyChangeRecordList list,
                                                                              final Interner<OWLAxiom> axiomInterner) {
        var changeRecords = list.getChangeRecords();
        var internedChangeRecordsListBuilder = ImmutableList.<OWLOntologyChangeRecord>builder();
        var changeDataInterner = new ChangeDataInterner(axiomInterner);
        for(var chanceRecord : changeRecords) {
            var ontologyId = chanceRecord.getOntologyID();
            var changeData = chanceRecord.getData();
            var internedChangeData = changeData.accept(changeDataInterner);
            if(internedChangeData == changeData) {
                internedChangeRecordsListBuilder.add(chanceRecord);
            }
            else {
                OWLOntologyChangeRecord rec = new OWLOntologyChangeRecord(ontologyId, internedChangeData);
                internedChangeRecordsListBuilder.add(rec);
            }
        }
        return internedChangeRecordsListBuilder.build();
    }

    private static class ChangeDataInterner implements OWLOntologyChangeDataVisitor<OWLOntologyChangeData, RuntimeException> {

        @Nonnull
        private final Interner<OWLAxiom> axiomInterner;

        public ChangeDataInterner(@Nonnull Interner<OWLAxiom> axiomInterner) {
            this.axiomInterner = checkNotNull(axiomInterner);
        }

        @Nonnull
        @Override
        public OWLOntologyChangeData visit(AddAxiomData data) throws RuntimeException {
            final OWLAxiom ax = axiomInterner.intern(data.getAxiom());
            if(ax != null) {
                return new AddAxiomData(ax);
            }
            else {
                return data;
            }
        }

        @Override
        public OWLOntologyChangeData visit(RemoveAxiomData data) throws RuntimeException {
            final OWLAxiom ax = axiomInterner.intern(data.getAxiom());
            if(ax != null) {
                return new RemoveAxiomData(ax);
            }
            else {
                return data;
            }
        }

        @Override
        public OWLOntologyChangeData visit(AddOntologyAnnotationData data) throws RuntimeException {
            return data;
        }

        @Override
        public OWLOntologyChangeData visit(RemoveOntologyAnnotationData data) throws RuntimeException {
            return data;
        }

        @Override
        public OWLOntologyChangeData visit(SetOntologyIDData data) throws RuntimeException {
            return data;
        }

        @Override
        public OWLOntologyChangeData visit(AddImportData data) throws RuntimeException {
            return data;
        }

        @Override
        public OWLOntologyChangeData visit(RemoveImportData data) throws RuntimeException {
            return data;
        }
    }
}
