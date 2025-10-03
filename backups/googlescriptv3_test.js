// Simple test to verify emoji handling

function testEmojiDisplay() {
  // CHANGE THIS to your test document ID
  const TEST_DOC_ID = 'YOUR_DOC_ID_HERE';
  
  const doc = DocumentApp.openById(TEST_DOC_ID);
  const body = doc.getBody();
  
  Logger.log('=== Testing Emoji Display ===');
  
  // Clear document
  body.clear();
  
  // Add test paragraphs
  body.appendParagraph('Test 1: Single clock emoji:');
  body.appendParagraph('⏰');
  body.appendParagraph('Test 2: Double clock emoji:');
  body.appendParagraph('⏰⏰');
  body.appendParagraph('Test 3: Stats with single clock:');
  const singleStats = '⏰\nLast edit: test\nStatus: test';
  body.appendParagraph(singleStats);
  body.appendParagraph('Test 4: Stats with double clock:');
  const doubleStats = '⏰⏰\nLast edit: test\nStatus: test';
  body.appendParagraph(doubleStats);
  
  // Now read them back
  const paras = body.getParagraphs();
  for (let i = 0; i < paras.length; i++) {
    const text = paras[i].getText();
    Logger.log('Paragraph ' + i + ' text: "' + text + '"');
    Logger.log('Paragraph ' + i + ' starts with ⏰: ' + text.startsWith('⏰'));
    Logger.log('Paragraph ' + i + ' starts with ⏰⏰: ' + text.startsWith('⏰⏰'));
    Logger.log('---');
  }
  
  // Test the actual replacement
  Logger.log('\n=== Testing Replacement ===');
  for (let i = 0; i < paras.length; i++) {
    const para = paras[i];
    const text = para.getText();
    if (text === '⏰⏰' || text.trim() === '⏰⏰') {
      Logger.log('Found ⏰⏰ at paragraph ' + i);
      const newText = '⏰⏰\nLast edit: REPLACED\nStatus: REPLACED';
      para.setText(newText);
      Logger.log('Replaced with: "' + newText + '"');
      Logger.log('After replacement, text is: "' + para.getText() + '"');
    }
  }
}