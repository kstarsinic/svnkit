package org.tmatesoft.svn.core.internal.ws.log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.diff.ISVNRAData;
import org.tmatesoft.svn.core.diff.SVNDiffWindow;
import org.tmatesoft.svn.core.internal.ws.fs.SVNRAFileData;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNCommitInfo;
import org.tmatesoft.svn.core.io.SVNException;
import org.tmatesoft.svn.core.io.SVNNodeKind;
import org.tmatesoft.svn.util.PathUtil;

public class SVNUpdateEditor implements ISVNEditor {
    
    private String mySwitchURL;
    private String myTarget;
    private String myTargetURL;
    private boolean myIsRecursive;
    private SVNWCAccess myWCAccess;
    
    private SVNDirectoryInfo myCurrentDirectory;
    private SVNFileInfo myCurrentFile;
    private long myTargetRevision;
    private boolean myIsRootOpen;
    private boolean myIsTargetDeleted;
    
    public SVNUpdateEditor(SVNWCAccess wcAccess, String switchURL, boolean recursive) throws SVNException {
        myWCAccess = wcAccess;
        myIsRecursive = recursive;
        myTarget = wcAccess.getTargetName();
        mySwitchURL = switchURL;
        
        SVNEntry entry = wcAccess.getAnchor().getEntries().getEntry("");
        myTargetURL = entry.getURL();
        if (myTarget != null) {
            myTargetURL = PathUtil.append(myTargetURL, PathUtil.encode(myTarget));
        }
        wcAccess.getTarget().getEntries().close();

        if ("".equals(myTarget)) {
            myTarget = null;
        }
    }

    public void targetRevision(long revision) throws SVNException {
        myTargetRevision = revision;
    }

    public void openRoot(long revision) throws SVNException {
        myIsRootOpen = true;
        myCurrentDirectory = createDirectoryInfo(null, "", false);
        if (myTarget == null) {
            SVNEntries entries = myCurrentDirectory.getDirectory().getEntries();
            SVNEntry entry = entries.getEntry("");
            entry.setRevision(myTargetRevision);
            entry.setURL(myCurrentDirectory.URL);
            entry.setIncomplete(true);
            entries.save(true);
        }
    }

    public void deleteEntry(String path, long revision) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);

        SVNLog log = myCurrentDirectory.getLog(true);
        Map attributes = new HashMap();
        String name = PathUtil.tail(path);
        
        attributes.put(SVNLog.NAME_ATTR, name);
        log.addCommand(SVNLog.DELETE_ENTRY, attributes, false);
        if (path.equals(myTarget)) {
            String kind = myCurrentDirectory.getDirectory().getFile(name, false).isFile() ? 
                    SVNProperty.KIND_FILE : SVNProperty.KIND_DIR;
            attributes.put(SVNLog.NAME_ATTR, name);
            attributes.put(SVNProperty.shortPropertyName(SVNProperty.KIND), kind);
            attributes.put(SVNProperty.shortPropertyName(SVNProperty.REVISION), Long.toString(myTargetRevision));
            attributes.put(SVNProperty.shortPropertyName(SVNProperty.DELETED), Boolean.TRUE.toString());
            log.addCommand(SVNLog.MODIFY_ENTRY, attributes, false);
            myIsTargetDeleted = true;
        }
        if (mySwitchURL != null) {
            myCurrentDirectory.getDirectory().destroy(name, true);
        }
        log.save();
        myCurrentDirectory.runLogs();
        myWCAccess.svnEvent(SVNEvent.createUpdateDeleteEvent(myWCAccess, myCurrentDirectory.getDirectory(), name));
    }

    public void addDir(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);

        SVNDirectory parentDir = myCurrentDirectory.getDirectory();
        myCurrentDirectory = createDirectoryInfo(myCurrentDirectory, path, true);
        
        String name = PathUtil.tail(path);
        File file = parentDir.getFile(name, false);
        if (file.exists()) {
            System.out.println("failed to add dir: " + file.getAbsolutePath());
            SVNErrorManager.error(0, null);
        } else if (".svn".equals(name)) {
            SVNErrorManager.error(0, null);
        } 
        SVNEntry entry = parentDir.getEntries().getEntry(name);
        if (entry != null) {
            if (entry.isScheduledForAddition()) {
                SVNErrorManager.error(0, null);
            }            
        } else {
            entry = parentDir.getEntries().addEntry(name);
        }
        entry.setKind(SVNNodeKind.DIR);
        entry.setAbsent(false);
        entry.setDeleted(false);
        parentDir.getEntries().save(true);
        
        SVNDirectory dir = parentDir.createChildDirectory(name, myCurrentDirectory.URL, myTargetRevision);
        if (dir == null) {
            SVNErrorManager.error(0, null);
        }
        dir.lock();
        myWCAccess.svnEvent(SVNEvent.createUpdateAddEvent(myWCAccess, parentDir, entry));
    }

    public void openDir(String path, long revision) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);

        myCurrentDirectory = createDirectoryInfo(myCurrentDirectory, path, false);
        SVNEntries entries = myCurrentDirectory.getDirectory().getEntries();
        SVNEntry entry = entries.getEntry("");
        entry.setRevision(myTargetRevision);
        entry.setURL(myCurrentDirectory.URL);
        entry.setIncomplete(true);
        entries.save(true);
        if (mySwitchURL != null) {
            changeDirProperty(SVNProperty.WC_URL, null);
        }        
    }

    public void absentDir(String path) throws SVNException {
        absentEntry(path, SVNNodeKind.DIR);
    }

    public void absentFile(String path) throws SVNException {
        absentEntry(path, SVNNodeKind.FILE);
    }
    
    private void absentEntry(String path, SVNNodeKind kind) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);

        String name = PathUtil.tail(path);
        SVNEntries entries = myCurrentDirectory.getDirectory().getEntries();
        SVNEntry entry = entries.getEntry(name);
        if (entry != null && entry.isScheduledForAddition()) {
            SVNErrorManager.error(0, null);
        }
        if (entry == null) {
            entries.addEntry(name);
        }
        entry.setKind(kind);
        entry.setDeleted(false);
        entry.setRevision(myTargetRevision);
        entry.setAbsent(true);
        entries.save(true);        
        
    }

    public void changeDirProperty(String name, String value) throws SVNException {
        myCurrentDirectory.propertyChanged(name, value);
    }

    public void closeDir() throws SVNException {
        Map modifiedWCProps = myCurrentDirectory.getChangedWCProperties();
        Map modifiedEntryProps = myCurrentDirectory.getChangedEntryProperties();
        Map modifiedProps = myCurrentDirectory.getChangedProperties();
        
        SVNEventStatus propStatus = SVNEventStatus.UNCHANGED;
        SVNDirectory dir = myCurrentDirectory.getDirectory();
        if (modifiedWCProps != null || modifiedEntryProps != null || modifiedProps != null) {
            SVNLog log = myCurrentDirectory.getLog(true);
    
            if (modifiedProps != null && !modifiedProps.isEmpty()) {
                SVNProperties props = dir.getProperties("", false);
                Map locallyModified = dir.getBaseProperties("", false).compareTo(props);
                myWCAccess.addExternals(dir, (String) modifiedProps.get(SVNProperty.EXTERNALS));
                
                propStatus = dir.mergeProperties("", modifiedProps, locallyModified, log);
                if (locallyModified == null || locallyModified.isEmpty()) {
                    Map command = new HashMap();
                    command.put(SVNLog.NAME_ATTR, "");
                    command.put(SVNProperty.shortPropertyName(SVNProperty.PROP_TIME), SVNLog.WC_TIMESTAMP);
                    log.addCommand(SVNLog.MODIFY_ENTRY, command, false);
                }
            }
            log.logChangedEntryProperties("", modifiedEntryProps);
            log.logChangedWCProperties("", modifiedWCProps);
            log.save();
        }
        myCurrentDirectory.runLogs();
        completeDirectory(myCurrentDirectory);
        if (!myCurrentDirectory.IsAdded && propStatus != SVNEventStatus.UNCHANGED) {
            myWCAccess.svnEvent(SVNEvent.createUpdateModifiedEvent(myWCAccess, dir, "", SVNEventAction.UPDATE_UPDATE, 
                    null, SVNEventStatus.UNCHANGED, propStatus, null));
        }
        myCurrentDirectory = myCurrentDirectory.Parent;
    }

    public SVNCommitInfo closeEdit() throws SVNException {
        if (!myIsRootOpen) {
            completeDirectory(myCurrentDirectory);
        }
        if (!myIsTargetDeleted) {
            bumpDirectories();
        }
        return null;
    }

    public void addFile(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);
        myCurrentFile = createFileInfo(myCurrentDirectory, path, true);
    }

    public void openFile(String path, long revision) throws SVNException {
        path = PathUtil.removeLeadingSlash(path);
        path = PathUtil.removeTrailingSlash(path);
        myCurrentFile = createFileInfo(myCurrentDirectory, path, false);
    }

    public void changeFileProperty(String name, String value) throws SVNException {
        myCurrentFile.propertyChanged(name, value);
        if (myWCAccess.getOptions().isUseCommitTimes() && SVNProperty.COMMITTED_DATE.equals(name)) {
            myCurrentFile.CommitTime = value; 
        }
    }

    public void applyTextDelta(String baseChecksum) throws SVNException {
        SVNDirectory dir = myCurrentFile.getDirectory();
        SVNEntries entries = dir.getEntries();
        SVNEntry entry = entries.getEntry(myCurrentFile.Name);
        File baseFile = dir.getBaseFile(myCurrentFile.Name, false);
        if (entry != null && entry.getChecksum() != null) {
            String realChecksum = null;
            try {
                realChecksum = SVNFileUtil.computeChecksum(baseFile);
            } catch (IOException e) {
                SVNErrorManager.error(0, e);
            }
            if (baseChecksum != null && (realChecksum == null || !realChecksum.equals(baseChecksum))) {
                SVNErrorManager.error(0, null);
            }
        }
        File baseTmpFile = dir.getBaseFile(myCurrentFile.Name, true);
        try {
            SVNFileUtil.copy(baseFile, baseTmpFile, false);
            if (!baseTmpFile.exists()) {
                baseTmpFile.createNewFile();
            }
        } catch (IOException e) {
            SVNErrorManager.error(0, e);
        }
    }

    public OutputStream textDeltaChunk(SVNDiffWindow diffWindow) throws SVNException {
        if (myCurrentFile.myDiffWindows == null) {
            myCurrentFile.myDiffWindows = new ArrayList();
        }
        int number = myCurrentFile.myDiffWindows.size();
        File file = myCurrentFile.getDirectory().getBaseFile(myCurrentFile.Name + "." + number + ".txtdelta", true);
        myCurrentFile.myDiffWindows.add(diffWindow);
        try {
            return new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            SVNErrorManager.error(0, e);
        }
        SVNErrorManager.error(0, null);
        return null;
    }

    public void textDeltaEnd() throws SVNException {
        if (myCurrentFile.myDiffWindows == null) {
            return;
        }
        int index = 0;
        File baseTmpFile = myCurrentFile.getDirectory().getBaseFile(myCurrentFile.Name, true);
        File targetFile = myCurrentFile.getDirectory().getBaseFile(myCurrentFile.Name + ".tmp", true);
        ISVNRAData baseData = new SVNRAFileData(baseTmpFile, false);
        ISVNRAData target = new SVNRAFileData(targetFile, false);
        for (Iterator windows = myCurrentFile.myDiffWindows.iterator(); windows.hasNext();) {
            SVNDiffWindow window = (SVNDiffWindow) windows.next();
            File dataFile = myCurrentFile.getDirectory().getBaseFile(myCurrentFile.Name + "." + index + ".txtdelta", true);
            InputStream data = null;
            try {
                data = new FileInputStream(dataFile);
                window.apply(baseData, target, data, target.length());
            } catch (FileNotFoundException e) {
                SVNErrorManager.error(0, e);
            } finally {
                if (data != null) {
                    try {
                        data.close();
                    } catch (IOException e) {
                    }
                }
            }
            dataFile.delete();
            index++;
        }
        try {
            target.close();
            baseData.close();
        } catch (IOException e) {
            SVNErrorManager.error(0, e);
        }
        try {
            SVNFileUtil.rename(targetFile, baseTmpFile);
        } catch (IOException e) {
            SVNErrorManager.error(0, e);
        }
    }

    public void closeFile(String textChecksum) throws SVNException {
        // check checksum.
        String checksum = null;
        if (myCurrentFile.myDiffWindows != null && textChecksum != null) {
            File baseTmpFile = myCurrentFile.getDirectory().getBaseFile(myCurrentFile.Name, true);
            try {
                checksum = SVNFileUtil.computeChecksum(baseTmpFile);
            } catch (IOException e) {
                SVNErrorManager.error(0, e);
            }
            if (!textChecksum.equals(checksum)) {
                SVNErrorManager.error(0, null);
            }
        }
        SVNDirectory dir = myCurrentFile.getDirectory();
        SVNLog log = myCurrentDirectory.getLog(true);
        Map command = new HashMap();
        
        // merge props.
        Map modifiedWCProps = myCurrentFile.getChangedWCProperties();
        Map modifiedEntryProps = myCurrentFile.getChangedEntryProperties();
        Map modifiedProps = myCurrentFile.getChangedProperties();
        
        SVNEventStatus textStatus = SVNEventStatus.UNCHANGED;
        SVNEventStatus lockStatus = SVNEventStatus.LOCK_UNCHANGED;
        
        boolean magicPropsChanged = false;
        SVNProperties props = dir.getProperties(myCurrentFile.Name, false);
        Map locallyModifiedProps = dir.getBaseProperties(myCurrentFile.Name, false).compareTo(props);
        if (modifiedProps != null && !modifiedProps.isEmpty()) {
            magicPropsChanged = modifiedProps.containsKey(SVNProperty.EXECUTABLE) ||
                modifiedProps.containsKey(SVNProperty.NEEDS_LOCK) ||
                modifiedProps.containsKey(SVNProperty.KEYWORDS) ||
                modifiedProps.containsKey(SVNProperty.EOL_STYLE) ||
                modifiedProps.containsKey(SVNProperty.SPECIAL);
        }
        SVNEventStatus propStatus = dir.mergeProperties(myCurrentFile.Name, modifiedProps, locallyModifiedProps, log);
        if (modifiedEntryProps != null) {
            lockStatus = log.logChangedEntryProperties(myCurrentFile.Name, modifiedEntryProps);
        }
        
        // merge contents.
        File textBase = dir.getBaseFile(myCurrentFile.Name, false);
        File textTmpBase = dir.getBaseFile(myCurrentFile.Name, true);
        String tmpPath = ".svn/tmp/text-base/" + myCurrentFile.Name + ".svn-base";
        String basePath = ".svn/text-base/" + myCurrentFile.Name + ".svn-base";
        
        if (!textTmpBase.exists() && magicPropsChanged) {
            // only props were changed, but we have to retranslate file.
            command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
            command.put(SVNLog.DEST_ATTR, tmpPath);
            log.addCommand(SVNLog.COPY_AND_DETRANSLATE, command, false);
            command.clear();
            command.put(SVNLog.NAME_ATTR, tmpPath);
            command.put(SVNLog.DEST_ATTR, myCurrentFile.Name);
            log.addCommand(SVNLog.COPY_AND_TRANSLATE, command, false);
            command.clear();
        }
        // update entry.
        command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
        command.put(SVNProperty.shortPropertyName(SVNProperty.KIND), SVNProperty.KIND_FILE);
        command.put(SVNProperty.shortPropertyName(SVNProperty.REVISION), Long.toString(myTargetRevision));
        command.put(SVNProperty.shortPropertyName(SVNProperty.DELETED), Boolean.FALSE.toString());
        command.put(SVNProperty.shortPropertyName(SVNProperty.ABSENT), Boolean.FALSE.toString());
        command.put(SVNProperty.shortPropertyName(SVNProperty.URL), myCurrentFile.URL);
        log.addCommand(SVNLog.MODIFY_ENTRY, command, false);
        command.clear();

        boolean isLocallyModified = !myCurrentFile.IsAdded && dir.hasTextModifications(myCurrentFile.Name, false);
        File workingFile = dir.getFile(myCurrentFile.Name, false);
        if (textTmpBase.exists()) {
            textStatus = SVNEventStatus.CHANGED;
            // there is a text replace working copy with.
            if (!isLocallyModified || !workingFile.exists()) {
                command.put(SVNLog.NAME_ATTR, tmpPath);
                command.put(SVNLog.DEST_ATTR, myCurrentFile.Name);
                log.addCommand(SVNLog.COPY_AND_TRANSLATE, command, false);
                command.clear();            
            } else {
                SVNEntries entries = dir.getEntries();
                SVNEntry entry = entries.getEntry(myCurrentFile.Name);
                String oldRevisionStr = ".r" + entry.getRevision();
                String newRevisionStr = ".r" + myTargetRevision;
                entries.close();
                command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
                command.put(SVNLog.ATTR1, basePath);
                command.put(SVNLog.ATTR2, tmpPath);
                command.put(SVNLog.ATTR3, oldRevisionStr);
                command.put(SVNLog.ATTR4, newRevisionStr);
                command.put(SVNLog.ATTR5, ".mine");
                log.addCommand(SVNLog.MERGE, command, false);
                command.clear();
                // do test merge.
                textStatus = dir.mergeText(myCurrentFile.Name, basePath, tmpPath, "", "", "", true);
            }
        } else if (lockStatus == SVNEventStatus.LOCK_UNLOCKED) {
            command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
            log.addCommand(SVNLog.MAYBE_READONLY, command, false);
            command.clear();            
        }
        if (locallyModifiedProps == null || locallyModifiedProps.isEmpty()) {
            command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
            command.put(SVNProperty.shortPropertyName(SVNProperty.PROP_TIME), SVNLog.WC_TIMESTAMP);
            log.addCommand(SVNLog.MODIFY_ENTRY, command, false);
            command.clear();            
        }
        if (textTmpBase.exists()) {
            command.put(SVNLog.NAME_ATTR, tmpPath);
            command.put(SVNLog.DEST_ATTR, basePath);
            log.addCommand(SVNLog.MOVE, command, false);
            command.clear();            
            command.put(SVNLog.NAME_ATTR, basePath);
            log.addCommand(SVNLog.READONLY, command, false);
            command.clear();
            command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
            command.put(SVNProperty.shortPropertyName(SVNProperty.CHECKSUM), checksum);
            log.addCommand(SVNLog.MODIFY_ENTRY, command, false);
            command.clear();
        }
        if (modifiedWCProps != null) {
            log.logChangedWCProperties(myCurrentFile.Name, modifiedWCProps);
        }
        if (!isLocallyModified) {
            if (myCurrentFile.CommitTime != null) {
                command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
                command.put(SVNLog.TIMESTAMP_ATTR, myCurrentFile.CommitTime);
                log.addCommand(SVNLog.SET_TIMESTAMP, command, false);
                command.clear();
            }
            if (textTmpBase.exists() || magicPropsChanged) {
                command.put(SVNLog.NAME_ATTR, myCurrentFile.Name);
                command.put(SVNProperty.shortPropertyName(SVNProperty.TEXT_TIME), SVNLog.WC_TIMESTAMP);
                log.addCommand(SVNLog.MODIFY_ENTRY, command, false);
                command.clear();
            }
        }        
        // bump.
        log.save();
        myCurrentFile.myDiffWindows = null;
        completeDirectory(myCurrentDirectory);
        // notify.
        SVNEventAction action = myCurrentFile.IsAdded ? SVNEventAction.UPDATE_ADD : SVNEventAction.UPDATE_UPDATE;
        myWCAccess.svnEvent(SVNEvent.createUpdateModifiedEvent(myWCAccess, dir, myCurrentFile.Name, action, null, 
                textStatus, propStatus, lockStatus));
        myCurrentFile = null;
    }

    public void abortEdit() throws SVNException {
    }
    
    private void bumpDirectories() throws SVNException {
        if (myIsTargetDeleted) {
            return;
        }
        SVNDirectory dir = myWCAccess.getTarget();
        if (myTarget != null){
            if (dir.getChildDirectory(myTarget) == null) {
                SVNEntry entry = dir.getEntries().getEntry(myTarget);
                boolean save = bumpEntry(dir.getEntries(), entry, mySwitchURL, myTargetRevision, false);
                if (save) {
                    dir.getEntries().save(true);
                } else {
                    dir.getEntries().close();
                }
                return;
            }
            dir = dir.getChildDirectory(myTarget);
        }
        bumpDirectory(dir, mySwitchURL);
    }
    
    private void bumpDirectory(SVNDirectory dir, String url) throws SVNException {
        SVNEntries entries = dir.getEntries();
        boolean save = bumpEntry(entries, entries.getEntry(""), url, myTargetRevision, false);
        Map childDirectories = new HashMap();
        for (Iterator ents = entries.entries(); ents.hasNext();) {
            SVNEntry entry = (SVNEntry) ents.next();
            if ("".equals(entry.getName())) {
                continue;
            }
            String childURL = url != null ? PathUtil.append(url, PathUtil.encode(entry.getName())) : null;
            if (entry.getKind() == SVNNodeKind.FILE) {
                save |= bumpEntry(entries, entry, childURL, myTargetRevision, true);
            } else if (myIsRecursive && entry.getKind() == SVNNodeKind.DIR) {
                SVNDirectory childDirectory = dir.getChildDirectory(entry.getName());
                if (!entry.isScheduledForAddition() && (childDirectory == null || !childDirectory.isVersioned())) {
                    myWCAccess.svnEvent(SVNEvent.createUpdateDeleteEvent(myWCAccess, dir, entry));
                    entries.deleteEntry(entry.getName());
                    save = true;
                } else {
                    // schedule for recursion, map of dir->url
                    childDirectories.put(childDirectory, childURL);
                }
            } 
        }
        if (save) {
            entries.save(true);
        }
        for (Iterator children = childDirectories.keySet().iterator(); children.hasNext();) {
            SVNDirectory child = (SVNDirectory) children.next();
            String childURL = (String) childDirectories.get(child);
            bumpDirectory(child, childURL);
        }
    }
    
    private static boolean bumpEntry(SVNEntries entries, SVNEntry entry, String url, long revision, boolean delete) {
        boolean save = false;
        if (url != null) {
            save |= entry.setURL(url);
        }
        if (revision >=0 && !entry.isScheduledForAddition() && !entry.isScheduledForDeletion()) {
            save |= entry.setRevision(revision);
        }
        if (delete && (entry.isDeleted() || (entry.isAbsent() && entry.getRevision() != revision))) {
            entries.deleteEntry(entry.getName());
            save = true;
        }
        return save;
    }
    
    private void completeDirectory(SVNDirectoryInfo info) throws SVNException {
        while(info != null) {
            info.RefCount--;
            if (info.RefCount > 0) {
                return;
            }
            if (info.Parent == null && myTarget != null) {
                return;
            }
            SVNEntries entries = info.getDirectory().getEntries();
            if (entries.getEntry("") == null) {
                SVNErrorManager.error(0, null);
            }
            for (Iterator ents = entries.entries(); ents.hasNext();) {
                SVNEntry entry = (SVNEntry) ents.next();
                if ("".equals(entry.getName())) {
                    entry.setIncomplete(false);
                    continue;
                }
                if (entry.isDeleted()) {
                    if (!entry.isScheduledForAddition()) {
                        entries.deleteEntry(entry.getName());
                    } else {
                        entry.setDeleted(false);
                    }
                } else if (entry.isAbsent() && entry.getRevision() != myTargetRevision) {
                    entries.deleteEntry(entry.getName());
                } else if (entry.getKind() == SVNNodeKind.DIR) {
                    SVNDirectory childDirectory = info.getDirectory().getChildDirectory(entry.getName());
                    if ((childDirectory == null || !childDirectory.isVersioned()) 
                            && !entry.isAbsent() && !entry.isScheduledForAddition()) {
                        myWCAccess.svnEvent(SVNEvent.createUpdateDeleteEvent(myWCAccess, info.getDirectory(), entry));
                        entries.deleteEntry(entry.getName());
                    }
                }
            }
            entries.save(true);
            info = info.Parent;
        }
    }
    
    private SVNFileInfo createFileInfo(SVNDirectoryInfo parent, String path, boolean added) throws SVNException {
        SVNFileInfo info = new SVNFileInfo(parent, path);
        info.IsAdded = added;
        info.Name = PathUtil.tail(path);
        SVNDirectory dir = parent.getDirectory();
        if (added && dir.getFile(info.Name, false).exists()) {
            SVNErrorManager.error(0, null);
        }
        SVNEntries entries = null;
        try {
            entries = dir.getEntries();
            SVNEntry entry = entries.getEntry(info.Name);
            if (added && entry != null && entry.isScheduledForAddition()) {
                SVNErrorManager.error(0, null);
            }
            if (!added && entry == null) {
                SVNErrorManager.error(0, null);
            }
            if (mySwitchURL != null || entry == null) {
                info.URL = PathUtil.append(parent.URL, PathUtil.encode(info.Name));
            } else if (entry != null) {
                info.URL = entry.getURL();
            } 
        } finally {
            if (entries != null) {
                entries.close();
            }
        }
        parent.RefCount++;
        return info;
    }
    
    private SVNDirectoryInfo createDirectoryInfo(SVNDirectoryInfo parent, String path, boolean added) throws SVNException {
        SVNDirectoryInfo info = new SVNDirectoryInfo(path);
        info.Parent = parent;
        info.IsAdded = added;
        String name = path != null ? PathUtil.tail(path) : "";

        if (mySwitchURL == null) {
            SVNDirectory dir = added ? null : info.getDirectory();
            if (dir != null && dir.getEntries().getEntry("") != null) {
                info.URL = dir.getEntries().getEntry("").getURL();
            }
            if (info.URL == null && parent != null) {
                info.URL = PathUtil.append(parent.URL, name);
            } else if (info.URL == null && parent == null) {
                info.URL = myTargetURL;
            }
        } else {
            if (parent == null) {
                info.URL = myTarget == null ? mySwitchURL : PathUtil.removeTail(mySwitchURL);
            } else {
                if (myTarget != null && parent.Parent == null) {
                    info.URL = mySwitchURL;
                } else {
                    info.URL = PathUtil.append(parent.URL, PathUtil.encode(name));
                }
            }
        }
        info.RefCount = 1;
        if (info.Parent != null) {
            info.Parent.RefCount++;
        }
        return info;
    }
    
    private class SVNEntryInfo {
        public String URL;
        public boolean IsAdded;
        public SVNDirectoryInfo Parent;
        
        private String myPath;        
        private Map myChangedProperties;
        private Map myChangedEntryProperties;
        private Map myChangedWCProperties;
        
        protected SVNEntryInfo(String path) {
            myPath = path;
        }
        
        protected String getPath() {
            return myPath;
        }
        
        public void propertyChanged(String name, String value) {
            if (name.startsWith(SVNProperty.SVN_ENTRY_PREFIX)) {
                myChangedEntryProperties = myChangedEntryProperties == null ? new HashMap() : myChangedEntryProperties;
                myChangedEntryProperties.put(name.substring(SVNProperty.SVN_ENTRY_PREFIX.length()), value);
            } else if (name.startsWith(SVNProperty.SVN_WC_PREFIX)) {
                myChangedWCProperties = myChangedWCProperties == null ? new HashMap() : myChangedWCProperties;
                myChangedWCProperties.put(name, value);
            } else {
                myChangedProperties = myChangedProperties == null ? new HashMap() : myChangedProperties;
                myChangedProperties.put(name, value);
            }
        }
        
        public Map getChangedWCProperties() {
            return myChangedWCProperties;
        }
        public Map getChangedEntryProperties() {
            return myChangedEntryProperties;
        }
        public Map getChangedProperties() {
            return myChangedProperties;
        }
    }
    
    private class SVNFileInfo extends SVNEntryInfo {
        
        public String Name;
        public String CommitTime;
        public Collection myDiffWindows;

        public SVNFileInfo(SVNDirectoryInfo parent, String path) {
            super(path);
            this.Parent = parent;
        }
        
        public SVNDirectory getDirectory() {
            return Parent.getDirectory();
        }
    }
    
    private class SVNDirectoryInfo extends SVNEntryInfo {
        
        public int RefCount;        
        private int myLogCount;        
        
        public SVNDirectoryInfo(String path) {
            super(path);
        }

        public SVNDirectory getDirectory() {
            return myWCAccess.getDirectory(getPath());
        }
        
        public SVNLog getLog(boolean increment) {
            SVNLog log = getDirectory().getLog(myLogCount);
            if (increment) {
                myLogCount++;
            }
            return log;
        }
        
        public void runLogs() throws SVNException {
            getDirectory().runLogs();
            myLogCount = 0;
        }
    }
}
