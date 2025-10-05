package com.vibecode.notestasher

object HubScriptTemplate {
    
    val MANIFEST_JSON = """
    {
      "timeZone": "UTC",
      "dependencies": {},
      "exceptionLogging": "STACKDRIVER",
      "runtimeVersion": "V8",
      "oauthScopes": [
        "https://www.googleapis.com/auth/documents",
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/script.scriptapp",
        "https://www.googleapis.com/auth/script.external_request"
      ]
    }
    """.trimIndent()

    val CODE_GS = """
// Hub Script for ReplyNote App - Manages multiple Google Docs

// ==================== Main Functions ====================

function installAll(cfg) {
  try {
    const props = PropertiesService.getScriptProperties();
    
    // Initialize hub_docIds if not exists
    if (!props.getProperty('hub_docIds')) {
      props.setProperty('hub_docIds', '[]');
    }
    
    // Set hub tick minutes
    const hubTickMinutes = cfg?.hubTickMinutes || 5;
    props.setProperty('hub_tick_minutes', String(hubTickMinutes));
    
    // Create or update the trigger
    deleteTriggers();
    ScriptApp.newTrigger('updateStatsTick')
      .timeBased()
      .everyMinutes(hubTickMinutes)
      .create();
    
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function registerDoc(docId, cfg) {
  try {
    const props = PropertiesService.getScriptProperties();
    
    // Get current doc IDs
    let docIds = JSON.parse(props.getProperty('hub_docIds') || '[]');
    
    // Check max docs limit
    if (docIds.length >= 3 && !docIds.includes(docId)) {
      return { ok: false, error: 'Maximum 3 documents allowed' };
    }
    
    // Add doc ID if not already registered
    if (!docIds.includes(docId)) {
      docIds.push(docId);
      props.setProperty('hub_docIds', JSON.stringify(docIds));
    }
    
    // Save per-doc config
    const prefix = 'doc_' + docId + '_';
    props.setProperty(prefix + 'statsTop', String(cfg?.statsTop || false));
    props.setProperty(prefix + 'statsBottom', String(cfg?.statsBottom !== false));
    props.setProperty(prefix + 'timezone', cfg?.timezone || Session.getScriptTimeZone());
    props.setProperty(prefix + 'auto', String(cfg?.autoEnabled || false));
    props.setProperty(prefix + 'minutes', String(clampMinutes(cfg?.minutes || 5)));
    props.setProperty(prefix + 'lastChangeTime', '0');
    props.setProperty(prefix + 'lastLiveTime', '0');
    props.setProperty(prefix + 'longestTime', '0');
    props.setProperty(prefix + 'lastContent', '');
    props.setProperty(prefix + 'nextRunAt', String(Date.now()));
    
    // Ensure markers in doc
    ensureMarkers(docId, cfg?.statsTop || false, cfg?.statsBottom !== false);
    
    return { ok: true, docId: docId };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function unregisterDoc(docId) {
  try {
    const props = PropertiesService.getScriptProperties();
    
    // Remove from doc IDs list
    let docIds = JSON.parse(props.getProperty('hub_docIds') || '[]');
    docIds = docIds.filter(id => id !== docId);
    props.setProperty('hub_docIds', JSON.stringify(docIds));
    
    // Delete all per-doc properties
    const prefix = 'doc_' + docId + '_';
    const allProps = props.getProperties();
    Object.keys(allProps).forEach(key => {
      if (key.startsWith(prefix)) {
        props.deleteProperty(key);
      }
    });
    
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function listDocs() {
  try {
    const props = PropertiesService.getScriptProperties();
    const docIds = JSON.parse(props.getProperty('hub_docIds') || '[]');
    
    const docs = docIds.map(docId => {
      const prefix = 'doc_' + docId + '_';
      return {
        docId: docId,
        statsTop: props.getProperty(prefix + 'statsTop') === 'true',
        statsBottom: props.getProperty(prefix + 'statsBottom') === 'true',
        timezone: props.getProperty(prefix + 'timezone') || Session.getScriptTimeZone(),
        auto: props.getProperty(prefix + 'auto') === 'true',
        minutes: parseInt(props.getProperty(prefix + 'minutes') || '5'),
        nextRunAt: parseInt(props.getProperty(prefix + 'nextRunAt') || '0')
      };
    });
    
    return { ok: true, docs: docs };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function applyConfig(docId, cfg) {
  try {
    const props = PropertiesService.getScriptProperties();
    const prefix = 'doc_' + docId + '_';
    
    // Update config
    if (cfg.statsTop !== undefined) {
      props.setProperty(prefix + 'statsTop', String(cfg.statsTop));
    }
    if (cfg.statsBottom !== undefined) {
      props.setProperty(prefix + 'statsBottom', String(cfg.statsBottom));
    }
    if (cfg.timezone !== undefined) {
      props.setProperty(prefix + 'timezone', cfg.timezone);
    }
    if (cfg.autoEnabled !== undefined) {
      props.setProperty(prefix + 'auto', String(cfg.autoEnabled));
    }
    if (cfg.minutes !== undefined) {
      props.setProperty(prefix + 'minutes', String(clampMinutes(cfg.minutes)));
    }
    
    // Update markers if stats config changed
    if (cfg.statsTop !== undefined || cfg.statsBottom !== undefined) {
      const statsTop = props.getProperty(prefix + 'statsTop') === 'true';
      const statsBottom = props.getProperty(prefix + 'statsBottom') === 'true';
      ensureMarkers(docId, statsTop, statsBottom);
    }
    
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function appendPlain(docId, payload) {
  try {
    const doc = DocumentApp.openById(docId);
    const body = doc.getBody();
    const props = PropertiesService.getScriptProperties();
    const prefix = 'doc_' + docId + '_';
    
    // Ensure markers
    const statsTop = payload.statsTop !== undefined ? payload.statsTop : 
                     (props.getProperty(prefix + 'statsTop') === 'true');
    const statsBottom = payload.statsBottom !== undefined ? payload.statsBottom : 
                       (props.getProperty(prefix + 'statsBottom') === 'true');
    ensureMarkers(docId, statsTop, statsBottom);
    
    // Insert content
    const insertIndex = getInsertIndex(body, statsBottom);
    body.insertParagraph(insertIndex, '-');
    body.insertParagraph(insertIndex + 1, '');
    body.insertParagraph(insertIndex + 2, payload.text);
    body.insertParagraph(insertIndex + 3, '');
    
    // Update timestamps
    const now = Date.now();
    props.setProperty(prefix + 'lastChangeTime', String(now));
    props.setProperty(prefix + 'lastLiveTime', String(now));
    
    // Update stats immediately
    updateStatsForDoc(docId);
    
    // Save content snapshot
    props.setProperty(prefix + 'lastContent', body.getText().substring(0, 1000));
    
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function appendBlocks(docId, payload) {
  try {
    const doc = DocumentApp.openById(docId);
    const body = doc.getBody();
    const props = PropertiesService.getScriptProperties();
    const prefix = 'doc_' + docId + '_';
    
    // Ensure markers
    const statsTop = payload.statsTop !== undefined ? payload.statsTop : 
                     (props.getProperty(prefix + 'statsTop') === 'true');
    const statsBottom = payload.statsBottom !== undefined ? payload.statsBottom : 
                       (props.getProperty(prefix + 'statsBottom') === 'true');
    ensureMarkers(docId, statsTop, statsBottom);
    
    // Parse and insert blocks
    const blocks = payload.blocks;
    const insertIndex = getInsertIndex(body, statsBottom);
    let currentIndex = insertIndex;
    
    // Add separator
    body.insertParagraph(currentIndex++, '-');
    body.insertParagraph(currentIndex++, '');
    
    // Insert each block
    blocks.forEach(block => {
      if (block.type === 'p' || block.type === 'paragraph') {
        body.insertParagraph(currentIndex++, block.text || '');
      } else if (block.type.startsWith('h')) {
        const para = body.insertParagraph(currentIndex++, block.text || '');
        const level = parseInt(block.type.charAt(1)) || 1;
        para.setHeading(getHeadingStyle(level));
      } else if (block.type === 'list_item') {
        const item = body.insertListItem(currentIndex++, block.text || '');
        item.setGlyphType(DocumentApp.GlyphType.BULLET);
      } else if (block.type === 'image' && block.dataUrl) {
        try {
          const base64 = block.dataUrl.split(',')[1];
          const blob = Utilities.newBlob(Utilities.base64Decode(base64), 'image/png');
          body.insertImage(currentIndex++, blob);
        } catch (e) {
          body.insertParagraph(currentIndex++, '[Image]');
        }
      }
    });
    
    body.insertParagraph(currentIndex++, '');
    
    // If last chunk, update timestamps
    if (payload.chunkIndex === payload.totalChunks - 1) {
      const now = Date.now();
      props.setProperty(prefix + 'lastChangeTime', String(now));
      props.setProperty(prefix + 'lastLiveTime', String(now));
      updateStatsForDoc(docId);
      props.setProperty(prefix + 'lastContent', body.getText().substring(0, 1000));
    }
    
    return { ok: true, nextIndex: payload.chunkIndex + 1 };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function updateStatsForDoc(docId) {
  try {
    const props = PropertiesService.getScriptProperties();
    const prefix = 'doc_' + docId + '_';
    
    const statsTop = props.getProperty(prefix + 'statsTop') === 'true';
    const statsBottom = props.getProperty(prefix + 'statsBottom') === 'true';
    
    if (!statsTop && !statsBottom) {
      return { ok: true };
    }
    
    const doc = DocumentApp.openById(docId);
    const body = doc.getBody();
    
    // Calculate times
    const now = Date.now();
    const lastChange = parseInt(props.getProperty(prefix + 'lastChangeTime') || '0');
    const lastLive = parseInt(props.getProperty(prefix + 'lastLiveTime') || '0');
    const longest = parseInt(props.getProperty(prefix + 'longestTime') || '0');
    
    const timeSinceChange = now - lastChange;
    const timeSinceLive = now - lastLive;
    
    // Update longest if needed
    if (timeSinceLive > longest) {
      props.setProperty(prefix + 'longestTime', String(timeSinceLive));
    }
    
    // Format stats text
    const tz = props.getProperty(prefix + 'timezone') || Session.getScriptTimeZone();
    const statsText = formatStats(timeSinceChange, Math.max(timeSinceLive, longest), tz);
    
    // Update markers
    updateMarker(body, true, statsTop ? statsText : null);
    updateMarker(body, false, statsBottom ? statsText : null);
    
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

function updateStatsTick() {
  try {
    const props = PropertiesService.getScriptProperties();
    const docIds = JSON.parse(props.getProperty('hub_docIds') || '[]');
    const now = Date.now();
    const updated = [];
    
    docIds.forEach(docId => {
      const prefix = 'doc_' + docId + '_';
      const nextRunAt = parseInt(props.getProperty(prefix + 'nextRunAt') || '0');
      
      if (now >= nextRunAt) {
        try {
          updateStatsForDoc(docId);
          updated.push(docId);
          
          // Set next run time
          const minutes = parseInt(props.getProperty(prefix + 'minutes') || '5');
          props.setProperty(prefix + 'nextRunAt', String(now + minutes * 60 * 1000));
        } catch (e) {
          console.error('Error updating doc ' + docId + ': ' + e);
        }
      }
    });
    
    return { ok: true, updated: updated };
  } catch (error) {
    return { ok: false, error: error.toString() };
  }
}

// ==================== Helper Functions ====================

function clampMinutes(minutes) {
  const allowed = [1, 5, 10, 15, 30];
  const parsed = parseInt(minutes) || 5;
  return allowed.reduce((prev, curr) => 
    Math.abs(curr - parsed) < Math.abs(prev - parsed) ? curr : prev
  );
}

function deleteTriggers() {
  const triggers = ScriptApp.getProjectTriggers();
  triggers.forEach(trigger => {
    if (trigger.getHandlerFunction() === 'updateStatsTick') {
      ScriptApp.deleteTrigger(trigger);
    }
  });
}

function ensureMarkers(docId, needsTop, needsBottom) {
  const doc = DocumentApp.openById(docId);
  const body = doc.getBody();
  
  if (needsTop && !hasMarker(body, true)) {
    body.insertParagraph(0, '⏰');
  }
  if (needsBottom && !hasMarker(body, false)) {
    body.appendParagraph('⏰');
  }
  if (!needsTop) {
    removeMarker(body, true);
  }
  if (!needsBottom) {
    removeMarker(body, false);
  }
}

function hasMarker(body, isTop) {
  const numChildren = body.getNumChildren();
  if (numChildren === 0) return false;
  
  const index = isTop ? 0 : numChildren - 1;
  const element = body.getChild(index);
  
  if (element.getType() === DocumentApp.ElementType.PARAGRAPH) {
    const text = element.asParagraph().getText();
    return text.startsWith('⏰');
  }
  return false;
}

function removeMarker(body, isTop) {
  if (hasMarker(body, isTop)) {
    const index = isTop ? 0 : body.getNumChildren() - 1;
    safeRemovePara(body, index);
  }
}

function updateMarker(body, isTop, text) {
  if (!text) {
    removeMarker(body, isTop);
    return;
  }
  
  const numChildren = body.getNumChildren();
  if (numChildren === 0) return;
  
  const index = isTop ? 0 : numChildren - 1;
  const element = body.getChild(index);
  
  if (element.getType() === DocumentApp.ElementType.PARAGRAPH) {
    const para = element.asParagraph();
    if (para.getText().startsWith('⏰')) {
      para.setText(text);
    } else if (isTop) {
      body.insertParagraph(0, text);
    } else {
      body.appendParagraph(text);
    }
  }
}

function safeRemovePara(body, index) {
  if (body.getNumChildren() > 1) {
    body.removeChild(body.getChild(index));
  } else {
    body.getChild(index).asParagraph().clear();
  }
}

function getInsertIndex(body, hasBottom) {
  const numChildren = body.getNumChildren();
  if (!hasBottom || numChildren === 0) {
    return numChildren;
  }
  
  const lastChild = body.getChild(numChildren - 1);
  if (lastChild.getType() === DocumentApp.ElementType.PARAGRAPH &&
      lastChild.asParagraph().getText().startsWith('⏰')) {
    return numChildren - 1;
  }
  
  return numChildren;
}

function getHeadingStyle(level) {
  const styles = [
    DocumentApp.ParagraphHeading.HEADING1,
    DocumentApp.ParagraphHeading.HEADING2,
    DocumentApp.ParagraphHeading.HEADING3,
    DocumentApp.ParagraphHeading.HEADING4,
    DocumentApp.ParagraphHeading.HEADING5,
    DocumentApp.ParagraphHeading.HEADING6
  ];
  return styles[Math.min(level - 1, 5)];
}

function formatStats(timeSinceChange, longestTime, timezone) {
  const formatTime = (ms) => {
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);
    
    if (days > 0) return days + ' day' + (days === 1 ? '' : 's');
    if (hours > 0) return hours + ' hour' + (hours === 1 ? '' : 's');
    if (minutes > 0) return minutes + ' minute' + (minutes === 1 ? '' : 's');
    return seconds + ' second' + (seconds === 1 ? '' : 's');
  };
  
  const lastEdit = formatTime(timeSinceChange);
  const longest = formatTime(longestTime);
  const now = new Date();
  const timeStr = Utilities.formatDate(now, timezone, 'HH:mm');
  
  return '⏰ Last edit: ' + lastEdit + ' ago | Longest: ' + longest + ' | Updated: ' + timeStr;
}
    """.trimIndent()
}