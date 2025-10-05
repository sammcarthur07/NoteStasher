package com.vibecode.notestasher

object SharedHubScriptTemplate {
    val CODE_GS = """
// Shared Hub Script for ReplyNote App (Fixed Sand Timer Version)

function doGet(e) { return handleRequest(e); }
function doPost(e) { return handleRequest(e); }

// ==================== MAIN HANDLERS ====================

function handleRequest(e) {
  try {
    const params = e.parameter;
    const token = params.token;
    const docId = params.docId;
    
    let action = params.action || 'append';
    let payload = null;
    if (e && e.postData && e.postData.contents) {
      try {
        payload = JSON.parse(e.postData.contents);
        if (payload.mode) {
          action = payload.mode;
        }
      } catch (err) {}
    }

    if (!token || !docId) {
      return createResponse({error: 'Invalid token or docId'}, 400);
    }
    
    switch(action) {
      case 'append':
        return handleAppend(token, docId, e, payload);
      case 'setConfig':
      case 'applyStatsSettings':
        return handleSetConfig(token, docId, params, payload);
      case 'registerDoc':
        return handleRegisterDoc(token, docId, params);
      case 'updateStats':
        const doc = DocumentApp.openById(docId);
        const config = getDocConfig(token, docId);
        updateStats(doc, config);
        return createResponse({success: true, message: 'Stats updated'});
      default:
        return createResponse({error: 'Unknown action: ' + action}, 400);
    }
  } catch (error) {
    Logger.log('Error in handleRequest: ' + error.toString() + ' Stack: ' + error.stack);
    return createResponse({error: 'Critical Error: ' + error.toString()}, 500);
  }
}

function handleAppend(token, docId, e, payload) {
  const doc = DocumentApp.openById(docId);
  const body = doc.getBody();
  const config = getDocConfig(token, docId);

  const content = e.parameter.text || (e.postData ? e.postData.contents : '');
  if (!content) return createResponse({error: 'No content to append'}, 400);

  const paras = body.getParagraphs();
  let insertAt = paras.length;
  if (config.statsBottom && paras.length > 0 && isStatsPara(paras[paras.length - 1])) {
    insertAt = paras.length - 1;
  }

  // --- THE FIX ---
  // Insert the separator and content with blank lines for correct spacing.
  body.insertParagraph(insertAt,     "—");
  body.insertParagraph(insertAt + 1, ""); // Adds blank line after separator
  body.insertParagraph(insertAt + 2, content);
  body.insertParagraph(insertAt + 3, ""); // Adds blank line after content

  updateStats(doc, config);
  return createResponse({ success: true, message: 'Content appended' });
}

function handleSetConfig(token, docId, params, payload) {
  const configKey = 'config_' + token + '_' + docId;
  const config = getDocConfig(token, docId);
  
  const settingsSource = payload || params;

  if (settingsSource.statsTop !== undefined) {
    config.statsTop = settingsSource.statsTop === true || settingsSource.statsTop === 'true';
  }
  if (settingsSource.statsBottom !== undefined) {
    config.statsBottom = settingsSource.statsBottom === true || settingsSource.statsBottom === 'true';
  }
  if (settingsSource.statsAnywhere !== undefined) {
    config.statsAnywhere = settingsSource.statsAnywhere === true || settingsSource.statsAnywhere === 'true';
  }
  if (settingsSource.timezone) config.timezone = settingsSource.timezone;
  
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  
  const doc = DocumentApp.openById(docId);
  updateStats(doc, config);
  return createResponse({success: true, config: config});
}

function handleRegisterDoc(token, docId, params) {
  const configKey = 'config_' + token + '_' + docId;
  const config = {
    statsTop: params.statsTop === 'true',
    statsBottom: params.statsBottom !== 'false',
    statsAnywhere: params.statsAnywhere === 'true',
    timezone: params.timezone || 'UTC',
  };
  PropertiesService.getScriptProperties().setProperty(configKey, JSON.stringify(config));
  
  const doc = DocumentApp.openById(docId);
  updateStats(doc, config);
  return createResponse({success: true, message: 'Document registered'});
}

// ==================== CORE STATS LOGIC (FINAL FLEXIBLE VERSION) ====================

function updateStats(doc, config) {
  try {
    const body = doc.getBody();
    
    let paras = body.getParagraphs();
    while (paras.length > 0 && paras[0].getText().trim() === '') {
        safeRemovePara(paras[0]);
        paras = body.getParagraphs();
    }
    
    const props = PropertiesService.getScriptProperties();
    const docId = doc.getId();

    if (paras.length === 0 && !config.statsTop && !config.statsBottom && !config.statsAnywhere) {
      return;
    }

    // --- Calculation Phase ---
    const lastContentKey = 'lastContent_' + docId;
    const lastChangeTimeKey = 'lastChangeTime_' + docId;
    const longestTimeKey = 'longestTime_' + docId;
    const lastLiveTimeKey = 'lastLiveTime_' + docId;
    let lastStoredContent = props.getProperty(lastContentKey) || '';
    let lastChangeTime = parseInt(props.getProperty(lastChangeTimeKey), 10) || null;
    let longestTime = parseInt(props.getProperty(longestTimeKey) || '0', 10);
    let lastLiveTime = parseInt(props.getProperty(lastLiveTimeKey) || '0', 10);
    let fullText = body.getText();
    let cleanText = fullText.replace(/[⏰⏳⌛️⏳️][\s\S]*?Status: .*?(?:\n|${'$'})/g, '');
    let bigChange = Math.abs(cleanText.length - lastStoredContent.length) > 5;
    const now = new Date();
    if (bigChange || !lastChangeTime) {
      lastChangeTime = now;
      if (bigChange) lastLiveTime = now;
    } else if (!lastLiveTime) {
      lastLiveTime = now;
    }
    const elapsedTime = Math.max(now - lastChangeTime, 0);
    const newLongestTime = Math.max(longestTime, elapsedTime);
    const tz = config.timezone || 'UTC';
    const timestampStr = Utilities.formatDate(new Date(lastChangeTime), tz, 'dd/MM/yyyy hh:mm:ss a');
    const status = elapsedTime < 2 * 60 * 1000 ? "Live" : "Away";
    const clockStatsText = '⏰\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;
    const sandTimerStatsText = '⏳\r\n' + 'Last edit: ' + timestampStr + ' — ' + formatElapsedTime(elapsedTime) + ' ago\n' + 'Longest time away: ' + formatElapsedTime(newLongestTime) + '\n' + 'Status: ' + status;

    // --- Top Stats Logic ---
    let topPara = (paras.length > 0 && isStatsPara(paras[0])) ? paras[0] : null;
    if (config.statsTop) {
      if (topPara) { topPara.setText(clockStatsText); } 
      else { body.insertParagraph(0, clockStatsText); }
    } else if (topPara) {
      safeRemovePara(topPara);
    }

    // --- Bottom Stats Logic ---
    const updatedParas = body.getParagraphs();
    const topIsNowStats = updatedParas.length > 0 && isStatsPara(updatedParas[0]);
    let bottomFound = false;
    for (let i = updatedParas.length - 1; i >= (topIsNowStats ? 1 : 0); i--) {
      if (isStatsPara(updatedParas[i])) {
        if (config.statsBottom) {
          updatedParas[i].setText(clockStatsText);
        } else {
          safeRemovePara(updatedParas[i]);
        }
        bottomFound = true;
        break;
      }
    }
    if (!bottomFound && config.statsBottom) {
      body.appendParagraph(clockStatsText);
    }
    
    // --- Anywhere Timer Stats Logic (Updated for flexible matching) ---
    const finalParas = body.getParagraphs();
    const ANYWHERE_MARKERS = ['⏳', '⏳️', '⌛️'];
    const PRIMARY_ANYWHERE_MARKER = '⏳';

    if (config.statsAnywhere === true) {
      for (let i = 0; i < finalParas.length; i++) {
        const para = finalParas[i];
        const paraText = para.getText();
        
        // NEW LOGIC: Check if the paragraph CONTAINS a marker, but is NOT already a stats block.
        const containsMarker = ANYWHERE_MARKERS.some(marker => paraText.includes(marker));
        const isAlreadyStats = paraText.includes('Last edit:');

        if (containsMarker && !isAlreadyStats) {
          para.setText(sandTimerStatsText);
        }
      }
    } else if (config.statsAnywhere === false) {
      for (let i = 0; i < finalParas.length; i++) {
        const text = finalParas[i].getText();
        if (text.startsWith(PRIMARY_ANYWHERE_MARKER + '\n') || text.startsWith(PRIMARY_ANYWHERE_MARKER + '\r') || text.startsWith(PRIMARY_ANYWHERE_MARKER + ' ')) {
          // Revert to a paragraph with only the marker.
          finalParas[i].setText(PRIMARY_ANYWHERE_MARKER);
        }
      }
    }
    
    // --- Save Properties ---
    props.setProperty(lastChangeTimeKey, lastChangeTime.toString());
    props.setProperty(lastLiveTimeKey, lastLiveTime.toString());
    props.setProperty(longestTimeKey, newLongestTime.toString());
    props.setProperty(lastContentKey, cleanText);
    
  } catch (e) {
    Logger.log('CRITICAL Error in updateStats: ' + e.toString() + ' Stack: ' + e.stack);
  }
}
// ==================== HELPER FUNCTIONS ====================

function getDocConfig(token, docId) {
  const configKey = 'config_' + token + '_' + docId;
  const configStr = PropertiesService.getScriptProperties().getProperty(configKey);
  if (configStr) return JSON.parse(configStr);
  return { statsTop: false, statsBottom: true, statsAnywhere: false, timezone: 'UTC' };
}

function isStatsPara(para) {
  try {
    // Only check for clock emoji, not sand timer
    return para && para.getType() === DocumentApp.ElementType.PARAGRAPH && para.getText().startsWith('⏰');
  } catch (e) { return false; }
}

function safeRemovePara(para) {
  try {
    const parent = para.getParent();
    const index = parent.getChildIndex(para);
    if (index === parent.getNumChildren() - 1) {
      para.clear(); 
    } else {
      para.removeFromParent();
    }
  } catch (e) {
    try { para.setText(''); } catch (e2) {}
  }
}

function formatElapsedTime(ms) {
    if (ms < 1000) return "0 sec";
    const sec = 1000, min = 60 * sec, hour = 60 * min, day = 24 * hour;
    let parts = [];
    const days = Math.floor(ms / day); if (days > 0) parts.push(days + (days > 1 ? ' days' : ' day')); ms %= day;
    const hours = Math.floor(ms / hour); if (hours > 0) parts.push(hours + (hours > 1 ? ' hours' : ' hour')); ms %= hour;
    const minutes = Math.floor(ms / min); if (minutes > 0) parts.push(minutes + ' min'); ms %= min;
    const seconds = Math.floor(ms / sec); if (seconds > 0 || parts.length === 0) parts.push(seconds + ' sec');
    return parts.join(', ');
}

function createResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data)).setMimeType(ContentService.MimeType.JSON);
}

/**
 * A dedicated test function to run our sand timer diagnostic.
 * This simulates unchecking the "Every ⏳" box.
 */
function testRemoveSandTimers() {
  const testParameters = {
    docId: '1s5LFnee5GdTKu0YbfKFfDVkg49GhGICs7z9lzdPkbcU',
    token: '92b2bedb-1371-4aaf-be7f-74bb8f3078bd', // Your specific token
    action: 'setConfig',
    statsAnywhere: 'false' // The action we want to test
  };

  const e = {
    parameter: testParameters
  };

  try {
    Logger.log("--- Starting testRemoveSandTimers ---");
    // This test will call the main handleRequest, which in turn runs the
    // updateStats function containing our new diagnostic logs.
    handleRequest(e); 
    Logger.log("--- Test Finished ---");
  } catch(err) {
    Logger.log("!!! ERROR in test function: " + err.toString());
  }
}


/**
 * A diagnostic helper to be called from a test function.
 * This is not currently used, as logging is now inside updateStats.
 * You can keep it for future use or remove it.
 */
function diagnoseSandTimerRemoval(docId) {
  // This function is for standalone diagnostics.
  // The main logging is now inside updateStats for a more integrated test.
}

/**
 * A test to inspect the saved settings for both documents.
 * BOTH DOCUMENT IDs ARE NOW CORRECTLY FILLED IN.
 */
function testInspectConfigs() {
  const docId1 = '1s5LFnee5GdTKu0YbfKFfDVkg49GhGICs7z9lzdPkbcU'; // The first doc
  const docId2 = '1EwAAB8mcltV_tmZiaBU0MpKEOsQDJEORCqZedD0Uio0';   // The second doc

  Logger.log("--- Inspecting Config for Document 1 ---");
  inspectConfigForDoc(docId1);
  
  Logger.log("\n--- Inspecting Config for Document 2 ---");
  inspectConfigForDoc(docId2);
}

/**
 * A test to simulate turning ON the sand timer for ONLY the second document.
 * THE DOCUMENT ID IS NOW CORRECTLY FILLED IN.
 */
function testEnableSandTimerOnDoc2() {
  const testParameters = {
    docId: '1EwAAB8mcltV_tmZiaBU0MpKEOsQDJEORCqZedD0Uio0', // The second doc
    token: '92b2bedb-1371-4aaf-bef-74bb8f3078bd',
    action: 'setConfig',
    statsAnywhere: 'true' // Explicitly turn this on
  };

  const e = { parameter: testParameters };

  try {
    Logger.log("--- Running testEnableSandTimerOnDoc2 ---");
    handleRequest(e);
    Logger.log("--- Test Finished ---");
  } catch (err) {
    Logger.log("!!! ERROR: " + err.toString());
  }
}

/**
 * Helper function that reads and logs the stored configuration for a given docId.
 */
function inspectConfigForDoc(docId) {
  try {
    const token = '92b2bedb-1371-4aaf-be7f-74bb8f3078bd'; // Your token
    const configKey = 'config_' + token + '_' + docId;
    const configStr = PropertiesService.getScriptProperties().getProperty(configKey);

    if (configStr) {
      Logger.log("Found saved settings for Doc ID " + docId);
      Logger.log("Raw JSON string: " + configStr);
      const config = JSON.parse(configStr);
      Logger.log("Parsed 'statsAnywhere' value is: " + config.statsAnywhere);
    } else {
      Logger.log("No saved settings found for Doc ID " + docId);
    }
  } catch(e) {
    Logger.log("!!! ERROR inspecting config: " + e.toString());
  }
}
""".trimIndent()

    val MANIFEST_JSON = """
{
  "timeZone": "UTC",
  "dependencies": {},
  "exceptionLogging": "STACKDRIVER",
  "runtimeVersion": "V8",
  "webapp": {
    "executeAs": "USER_DEPLOYING",
    "access": "ANYONE_ANONYMOUS"
  }
}
""".trimIndent()
    
    val DEPLOYMENT_INSTRUCTIONS = """
# Shared Hub Script Deployment Instructions

## For Developers (One-time setup):

1. Go to https://script.google.com
2. Create a new project
3. Delete all default code
4. Copy and paste the Code.gs content
5. Click File → Project Properties → Script Properties (if available)
6. Save the project
7. Click Deploy → New Deployment
8. Choose "Web app" as type
9. Set:
   - Execute as: Me
   - Who has access: Anyone
10. Click Deploy
11. Copy the Web App URL

## For App Users:

Just enter the Web App URL provided by the developer into the app.
No Google sign-in or authorization needed!

## Testing the Script:

1. Replace YOUR_TEST_DOC_ID_HERE in testSetup() with a real Google Doc ID
2. Run testSetup() function
3. Check execution logs
""".trimIndent()
}