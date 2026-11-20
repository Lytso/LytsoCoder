package brainctl

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

type rawField struct {
	num      uint64
	wireType uint8
	varint   uint64
	fixed64  [8]byte
	fixed32  [4]byte
	data     []byte
}

func consumeVarint(b []byte) (uint64, int, error) {
	var x uint64
	var s uint
	for i, c := range b {
		if i == 10 {
			return 0, 0, errors.New("proto varint overflow")
		}
		if c < 0x80 {
			return x | uint64(c)<<s, i + 1, nil
		}
		x |= uint64(c&0x7f) << s
		s += 7
	}
	return 0, 0, errors.New("unexpected EOF in proto varint")
}

func appendVarint(dst []byte, v uint64) []byte {
	for v >= 0x80 {
		dst = append(dst, byte(v)|0x80)
		v >>= 7
	}
	return append(dst, byte(v))
}

func decodeFields(b []byte) ([]rawField, error) {
	var fields []rawField
	i := 0
	for i < len(b) {
		tag, n, err := consumeVarint(b[i:])
		if err != nil {
			return nil, err
		}
		i += n
		num := tag >> 3
		wt := uint8(tag & 7)
		if num == 0 {
			return nil, errors.New("invalid proto field number 0")
		}
		f := rawField{num: num, wireType: wt}
		switch wt {
		case 0:
			v, vn, err := consumeVarint(b[i:])
			if err != nil {
				return nil, err
			}
			i += vn
			f.varint = v
		case 1:
			if i+8 > len(b) {
				return nil, errors.New("unexpected EOF in proto fixed64")
			}
			copy(f.fixed64[:], b[i:i+8])
			i += 8
		case 2:
			ln, lnN, err := consumeVarint(b[i:])
			if err != nil {
				return nil, err
			}
			i += lnN
			if ln > uint64(len(b)-i) {
				return nil, errors.New("unexpected EOF in proto length-delimited field")
			}
			data := make([]byte, int(ln))
			copy(data, b[i:i+int(ln)])
			f.data = data
			i += int(ln)
		case 5:
			if i+4 > len(b) {
				return nil, errors.New("unexpected EOF in proto fixed32")
			}
			copy(f.fixed32[:], b[i:i+4])
			i += 4
		default:
			return nil, fmt.Errorf("unsupported proto wire type %d", wt)
		}
		fields = append(fields, f)
	}
	return fields, nil
}

func encodeFields(fields []rawField) []byte {
	var out []byte
	for _, f := range fields {
		out = appendVarint(out, (f.num<<3)|uint64(f.wireType))
		switch f.wireType {
		case 0:
			out = appendVarint(out, f.varint)
		case 1:
			out = append(out, f.fixed64[:]...)
		case 2:
			out = appendVarint(out, uint64(len(f.data)))
			out = append(out, f.data...)
		case 5:
			out = append(out, f.fixed32[:]...)
		}
	}
	return out
}

func firstBytesField(fields []rawField, num uint64) ([]byte, bool) {
	for _, f := range fields {
		if f.num == num && f.wireType == 2 {
			return f.data, true
		}
	}
	return nil, false
}

func replaceOrAddBytesField(fields []rawField, num uint64, val []byte) []rawField {
	replaced := false
	out := make([]rawField, 0, len(fields)+1)
	for _, f := range fields {
		if f.num == num {
			if !replaced {
				out = append(out, rawField{num: num, wireType: 2, data: val})
				replaced = true
			}
			continue
		}
		out = append(out, f)
	}
	if !replaced {
		out = append(out, rawField{num: num, wireType: 2, data: val})
	}
	return out
}

func deleteFieldNums(fields []rawField, nums ...uint64) ([]rawField, bool) {
	drop := make(map[uint64]bool, len(nums))
	for _, n := range nums {
		drop[n] = true
	}
	changed := false
	out := make([]rawField, 0, len(fields))
	for _, f := range fields {
		if drop[f.num] {
			changed = true
			continue
		}
		out = append(out, f)
	}
	return out, changed
}

var (
	leadingHiddenRe    = regexp.MustCompile(`^(?:\s*<(?:lytsocoder|antigem)-expansion\s+command="">[\s\S]*?</(?:lytsocoder|antigem)-expansion>\s*)+`)
	trailingAttachedRe = regexp.MustCompile(`\s*<(?:lytsocoder|antigem)-expansion\s+command="">[\s\S]*?<attached-files>[\s\S]*?</(?:lytsocoder|antigem)-expansion>\s*$`)
)

// PreserveHiddenPromptWrappers keeps leading hidden GlobalContext blocks and trailing AttachedFiles blocks
// when the user edits only the visible portion of their prompt.
func PreserveHiddenPromptWrappers(existingRaw, newVisible string) string {
	var prefix, suffix string
	if m := leadingHiddenRe.FindStringIndex(existingRaw); m != nil && m[0] == 0 {
		// GlobalContext.wrap places `hidden(note) + text` with no separator, or a newline between blocks.
		prefix = existingRaw[:m[1]]
	}
	restStart := len(prefix)
	if m := trailingAttachedRe.FindStringIndex(existingRaw[restStart:]); m != nil {
		suffix = existingRaw[restStart+m[0] : restStart+m[1]]
	}
	if prefix == "" && suffix == "" {
		return newVisible
	}
	res := prefix + strings.TrimSpace(newVisible)
	if suffix != "" {
		if !strings.HasPrefix(suffix, "\n") {
			res += "\n\n"
		}
		res += suffix
	}
	return res
}

// RewriteUserInputStep updates CortexStepUserInput (Step field 19) with newText while keeping all other
// step metadata, scope items, media, and config fields intact.
func RewriteUserInputStep(payload []byte, newText string, preserveHiddenPrefix bool) ([]byte, error) {
	stepFields, err := decodeFields(payload)
	if err != nil {
		return nil, err
	}
	uiRaw, ok := firstBytesField(stepFields, 19)
	if !ok {
		return nil, errors.New("step has no user_input payload")
	}
	uiFields, err := decodeFields(uiRaw)
	if err != nil {
		return nil, fmt.Errorf("invalid user_input proto: %w", err)
	}

	existingText := ""
	if b, ok := firstBytesField(uiFields, 2); ok && len(b) > 0 {
		existingText = string(b)
	} else if itemRaw, ok := firstBytesField(uiFields, 3); ok {
		if itemFields, err := decodeFields(itemRaw); err == nil {
			if t, ok := firstBytesField(itemFields, 1); ok {
				existingText = string(t)
			}
		}
	} else if b, ok := firstBytesField(uiFields, 1); ok {
		existingText = string(b)
	}

	finalText := newText
	if preserveHiddenPrefix && existingText != "" {
		finalText = PreserveHiddenPromptWrappers(existingText, newText)
	}

	uiFields = replaceOrAddBytesField(uiFields, 2, []byte(finalText))
	if _, hasQuery := firstBytesField(uiFields, 1); hasQuery {
		uiFields = replaceOrAddBytesField(uiFields, 1, []byte(finalText))
	}

	// Update TextOrScopeItem (field 3): first item carrying text (field 1), keeping all scope items intact.
	updatedItem := false
	for idx := range uiFields {
		if uiFields[idx].num == 3 && uiFields[idx].wireType == 2 {
			itemFields, err := decodeFields(uiFields[idx].data)
			if err != nil {
				continue
			}
			if _, hasText := firstBytesField(itemFields, 1); hasText || !updatedItem {
				itemFields = replaceOrAddBytesField(itemFields, 1, []byte(finalText))
				uiFields[idx].data = encodeFields(itemFields)
				updatedItem = true
				break
			}
		}
	}
	if !updatedItem {
		newItem := encodeFields([]rawField{{num: 1, wireType: 2, data: []byte(finalText)}})
		uiFields = append(uiFields, rawField{num: 3, wireType: 2, data: newItem})
	}

	stepFields = replaceOrAddBytesField(stepFields, 19, encodeFields(uiFields))
	return encodeFields(stepFields), nil
}

// RewritePlannerResponseStep updates CortexStepPlannerResponse (Step field 20) visible response text (field 1),
// clears modified_response (field 8), and preserves thinking and tool_calls.
func RewritePlannerResponseStep(payload []byte, newText string) ([]byte, error) {
	stepFields, err := decodeFields(payload)
	if err != nil {
		return nil, err
	}
	prRaw, ok := firstBytesField(stepFields, 20)
	if !ok {
		return nil, errors.New("step has no planner_response payload")
	}
	prFields, err := decodeFields(prRaw)
	if err != nil {
		return nil, fmt.Errorf("invalid planner_response proto: %w", err)
	}
	prFields = replaceOrAddBytesField(prFields, 1, []byte(newText))
	prFields, _ = deleteFieldNums(prFields, 8)
	stepFields = replaceOrAddBytesField(stepFields, 20, encodeFields(prFields))
	return encodeFields(stepFields), nil
}

// StripThinkingFromStep deletes thinking/reasoning fields (3, 4, 5, 11, 14, 16) from CortexStepPlannerResponse
// while preserving visible response text and tool_calls.
func StripThinkingFromStep(payload []byte) ([]byte, bool, error) {
	stepFields, err := decodeFields(payload)
	if err != nil {
		return nil, false, err
	}
	prRaw, ok := firstBytesField(stepFields, 20)
	if !ok {
		return payload, false, nil
	}
	prFields, err := decodeFields(prRaw)
	if err != nil {
		return nil, false, fmt.Errorf("invalid planner_response proto: %w", err)
	}
	cleaned, changed := deleteFieldNums(prFields, 3, 4, 5, 11, 14, 16)
	if !changed {
		return payload, false, nil
	}
	stepFields = replaceOrAddBytesField(stepFields, 20, encodeFields(cleaned))
	return encodeFields(stepFields), true, nil
}

func matchesArtifactBytes(b []byte, artifactPath, artifactName string) bool {
	if len(b) == 0 {
		return false
	}
	s := string(b)
	cleanPath := strings.TrimPrefix(artifactPath, "file://")
	if cleanPath != "" && strings.Contains(s, cleanPath) {
		return true
	}
	if artifactPath != "" && artifactPath != cleanPath && strings.Contains(s, artifactPath) {
		return true
	}
	if len(artifactName) >= 4 && strings.Contains(s, artifactName) {
		return true
	}
	return false
}

func redactToolJSONArgs(rawJSON string, notice string) (string, bool) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(rawJSON), &obj); err != nil {
		return rawJSON, false
	}
	changed := false
	for _, key := range []string{"CodeContent", "codeContent", "code_content", "Content", "content", "ReplacementContent", "TargetContent"} {
		if v, ok := obj[key].(string); ok && v != "" && v != notice {
			obj[key] = notice
			changed = true
		}
	}
	if !changed {
		return rawJSON, false
	}
	b, err := json.Marshal(obj)
	if err != nil {
		return rawJSON, false
	}
	return string(b), true
}

func redactGenericMapPayload(data []byte, notice string, depth int) ([]byte, bool) {
	if depth > 4 || len(data) == 0 {
		return data, false
	}
	fields, err := decodeFields(data)
	if err != nil || len(fields) == 0 {
		return data, false
	}
	// Detect key-value map entries (field 1 string key + field 2 string value)
	if len(fields) == 2 && fields[0].num == 1 && fields[0].wireType == 2 && fields[1].num == 2 && fields[1].wireType == 2 {
		k := string(fields[0].data)
		v := string(fields[1].data)
		switch k {
		case "CodeContent", "codeContent", "code_content", "Content", "content", "ReplacementContent", "TargetContent":
			if v != notice {
				fields[1].data = []byte(notice)
				return encodeFields(fields), true
			}
			return data, false
		}
		if len(v) > 256 && !strings.HasPrefix(v, "/") && !strings.HasPrefix(v, "file://") {
			fields[1].data = []byte(notice)
			return encodeFields(fields), true
		}
	}

	changed := false
	for i := range fields {
		if fields[i].wireType != 2 || len(fields[i].data) == 0 {
			continue
		}
		if sub, subChanged := redactGenericMapPayload(fields[i].data, notice, depth+1); subChanged {
			fields[i].data = sub
			changed = true
			continue
		}
		// Large standalone string content block (not JSON args already handled elsewhere)
		if len(fields[i].data) > 512 && isPrintableUTF8(fields[i].data) {
			s := string(fields[i].data)
			if updatedJSON, ok := redactToolJSONArgs(s, notice); ok {
				fields[i].data = []byte(updatedJSON)
				changed = true
			}
		}
	}
	if !changed {
		return data, false
	}
	return encodeFields(fields), true
}

func isPrintableUTF8(b []byte) bool {
	if len(b) == 0 {
		return false
	}
	for _, r := range string(b[:min(len(b), 128)]) {
		if r == '\ufffd' || (r < ' ' && r != '\n' && r != '\r' && r != '\t') {
			return false
		}
	}
	return true
}

// RedactArtifactInStep replaces written file contents/diffs inside any Step referencing artifactPath or artifactName
// so deleted obsolete artifacts stop consuming model context while preserving step indexing.
func RedactArtifactInStep(payload []byte, artifactPath, artifactName string) ([]byte, bool, error) {
	if !matchesArtifactBytes(payload, artifactPath, artifactName) {
		return payload, false, nil
	}
	stepFields, err := decodeFields(payload)
	if err != nil {
		return nil, false, err
	}
	label := artifactName
	if label == "" {
		label = strings.TrimPrefix(artifactPath, "file://")
	}
	notice := fmt.Sprintf("[Artifact deleted by user: %s]", label)
	changed := false

	for i := range stepFields {
		if stepFields[i].wireType != 2 {
			continue
		}
		switch stepFields[i].num {
		case 23: // CortexStepWriteToFile
			if wf, err := decodeFields(stepFields[i].data); err == nil {
				wf = replaceOrAddBytesField(wf, 2, []byte(notice))
				wf, _ = deleteFieldNums(wf, 3)
				stepFields[i].data = encodeFields(wf)
				changed = true
			}
		case 5: // CortexStepMetadata -> repeated ChatToolCall at field 4
			if md, err := decodeFields(stepFields[i].data); err == nil {
				mdChanged := false
				for j := range md {
					if md[j].num == 4 && md[j].wireType == 2 {
						if tc, err := decodeFields(md[j].data); err == nil {
							for k := range tc {
								if tc[k].num == 3 && tc[k].wireType == 2 {
									if updated, ok := redactToolJSONArgs(string(tc[k].data), notice); ok {
										tc[k].data = []byte(updated)
										mdChanged = true
									}
								}
							}
							if mdChanged {
								md[j].data = encodeFields(tc)
							}
						}
					}
				}
				if mdChanged {
					stepFields[i].data = encodeFields(md)
					changed = true
				}
			}
		case 20: // CortexStepPlannerResponse -> tool_calls at field 7
			if pr, err := decodeFields(stepFields[i].data); err == nil {
				prChanged := false
				for j := range pr {
					if pr[j].num == 7 && pr[j].wireType == 2 {
						if tc, err := decodeFields(pr[j].data); err == nil {
							tcChanged := false
							for k := range tc {
								if tc[k].num == 3 && tc[k].wireType == 2 && matchesArtifactBytes(tc[k].data, artifactPath, artifactName) {
									if updated, ok := redactToolJSONArgs(string(tc[k].data), notice); ok {
										tc[k].data = []byte(updated)
										tcChanged = true
									}
								}
							}
							if tcChanged {
								pr[j].data = encodeFields(tc)
								prChanged = true
							}
						}
					}
				}
				if prChanged {
					stepFields[i].data = encodeFields(pr)
					changed = true
				}
			}
		case 140: // Generic tool args/reply blob in step_type 132
			if sub, subChanged := redactGenericMapPayload(stepFields[i].data, notice, 0); subChanged {
				stepFields[i].data = sub
				changed = true
			}
		}
	}

	if !changed {
		return payload, false, nil
	}
	return encodeFields(stepFields), true, nil
}

// Helper used by tests to construct fixed fields cleanly if needed.
var _ = binary.LittleEndian
