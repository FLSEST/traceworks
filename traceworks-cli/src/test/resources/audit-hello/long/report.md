# Long files

8 file(s) have more than 10 lines:

- `README.md`: 12 lines
- `dir/with space.txt`: 11 lines
- `tab	here.txt`: 13 lines
- `quote"back\slash.txt`: 14 lines
- `été/日本語.md`: 15 lines
- `emoji 😀.txt`: 16 lines
- `ctrldel.txt`: 17 lines
- `bad�.txt`: 18 lines

gate_status: YELLOW
gate_category: hello
gate_description: 8 of the files are longer than 10 lines

<!-- traceworks:audit-result:v1 {"snapshotComplete": true, "expectedFindings": 8, "findings": [{"ruleKey": "long-file", "subject": "README.md", "severity": "LOW", "title": "README.md has 12 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: README.md (12 lines)"}, {"ruleKey": "long-file", "subject": "dir/with space.txt", "severity": "LOW", "title": "dir/with space.txt has 11 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: dir/with space.txt (11 lines)"}, {"ruleKey": "long-file", "subject": "tab\there.txt", "severity": "LOW", "title": "tab\there.txt has 13 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: tab\there.txt (13 lines)"}, {"ruleKey": "long-file", "subject": "quote\"back\\slash.txt", "severity": "LOW", "title": "quote\"back\\slash.txt has 14 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: quote\"back\\slash.txt (14 lines)"}, {"ruleKey": "long-file", "subject": "\u00e9t\u00e9/\u65e5\u672c\u8a9e.md", "severity": "LOW", "title": "\u00e9t\u00e9/\u65e5\u672c\u8a9e.md has 15 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: \u00e9t\u00e9/\u65e5\u672c\u8a9e.md (15 lines)"}, {"ruleKey": "long-file", "subject": "emoji \ud83d\ude00.txt", "severity": "LOW", "title": "emoji \ud83d\ude00.txt has 16 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: emoji \ud83d\ude00.txt (16 lines)"}, {"ruleKey": "long-file", "subject": "ctrl\u001bdel\u007f.txt", "severity": "LOW", "title": "ctrl\u001bdel\u007f.txt has 17 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: ctrl\u001bdel\u007f.txt (17 lines)"}, {"ruleKey": "long-file", "subject": "bad\ufffd.txt", "severity": "LOW", "title": "bad\ufffd.txt has 18 lines, more than 10", "description": "The example counts every file with more than 10 lines as a finding.", "evidence": "long_file: bad\ufffd.txt (18 lines)"}]} -->
