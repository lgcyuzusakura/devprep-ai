"""Merge an attributed Chinese choice pack without regenerating existing translations.

This command is local-only and makes no network or model requests.
"""
import argparse
import copy
import json
import re
from pathlib import Path


def merge_choices(base, extra):
    for pack in (base, extra):
        if not isinstance(pack, dict) or not all(isinstance(pack.get(k), list) for k in ('topics', 'questions')):
            raise ValueError('Pack requires topics and questions arrays')
        for key in ('topics', 'questions'):
            ids = [row.get('id') for row in pack[key]]
            if any(not isinstance(value, str) or not value.strip() for value in ids) or len(set(ids)) != len(ids):
                raise ValueError(f'Invalid or duplicate IDs in {key}')
    topics = {t['id']: t for t in base['topics']}
    questions = {q['id']: q for q in base['questions']}
    for topic in extra['topics']:
        if not all(isinstance(topic.get(k), str) and topic[k].strip() for k in ('domain', 'title', 'summary')):
            raise ValueError('Topic needs domain, title and summary')
        if topic['id'] in topics and topics[topic['id']] != topic:
            raise ValueError(f"Conflicting topic ID: {topic['id']}")
        topics[topic['id']] = topic
    for question in extra['questions']:
        options = question.get('options')
        if question.get('type') != 'single_choice' or question.get('content_locale') != 'zh-CN':
            raise ValueError('Additional questions must be Chinese single-choice questions')
        if not isinstance(options, list) or len(options) < 2 or any(not isinstance(v, str) or not v.strip() for v in options):
            raise ValueError('Invalid options')
        if len(set(options)) != len(options) or question.get('answer') not in options:
            raise ValueError('Options must be unique and include the exact answer')
        if question.get('point') not in topics:
            raise ValueError('Question references an unknown topic')
        if not isinstance(question.get('stem'), str) or not re.search(r'[\u4e00-\u9fff]', question['stem']):
            raise ValueError('Question stem must contain Chinese text')
        for field in ('explanation', 'source_name', 'source_url', 'source_attribution'):
            if not isinstance(question.get(field), str) or not question[field].strip():
                raise ValueError(f'Missing {field}')
        if question['id'] in questions and questions[question['id']] != question:
            raise ValueError(f"Conflicting question ID: {question['id']}")
        questions[question['id']] = question
    return copy.deepcopy({**base, 'topics': list(topics.values()), 'questions': list(questions.values())})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--choices', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = json.loads(args.base.read_text(encoding='utf-8-sig'))
    extra = json.loads(args.choices.read_text(encoding='utf-8-sig'))
    merged = merge_choices(base, extra)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + '.tmp')
    temporary.write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(args.output)
    print(json.dumps({'added_questions': len(merged['questions'])-len(base['questions']),
                      'total_questions': len(merged['questions']), 'model_calls': 0}))


if __name__ == '__main__':
    main()
