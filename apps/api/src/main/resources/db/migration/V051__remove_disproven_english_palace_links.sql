-- BA-086 / A-03: rc.26 public audit found that the reviewed English ids for two palaces
-- identify their gates instead. Remove only those exact link decisions and text written by
-- KTO_ENG_SERVICE for them. Korean rows and other English sources remain untouched.
DELETE FROM place_localizations text
USING place_localization_sources link
WHERE text.place_id = link.place_id
  AND text.locale = 'en'
  AND text.source_code = 'KTO_ENG_SERVICE'
  AND link.locale = 'en'
  AND link.source_code = 'KTO_ENG_SERVICE'
  AND link.external_type = 'KTO_CONTENT_TYPE:76'
  AND ((link.place_id = '01a0b825-4f15-7e7b-b30c-87cf71861c9c'::uuid AND link.external_id = '264329')
    OR (link.place_id = '01a0b9f7-8020-74c6-bdca-dac05aadc82e'::uuid AND link.external_id = '1942577'));

DELETE FROM place_localization_sources link
WHERE link.locale = 'en'
  AND link.source_code = 'KTO_ENG_SERVICE'
  AND link.external_type = 'KTO_CONTENT_TYPE:76'
  AND ((link.place_id = '01a0b825-4f15-7e7b-b30c-87cf71861c9c'::uuid AND link.external_id = '264329')
    OR (link.place_id = '01a0b9f7-8020-74c6-bdca-dac05aadc82e'::uuid AND link.external_id = '1942577'));
