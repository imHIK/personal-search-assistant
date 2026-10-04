export interface KnownCompany {
  label: string
  handle: string
  /** Display only; never sent to the server. */
  platform: string
  /** The company's own site, for its logo. Absent shows a monogram. */
  domain?: string
}

export const knownCompanies: KnownCompany[] = [
  { label: 'Abnormal Security', handle: 'abnormalsecurity', platform: 'greenhouse', domain: 'abnormalsecurity.com' },
  { label: 'Acko', handle: 'acko', platform: 'kula', domain: 'acko.com' },
  { label: 'Adobe', handle: 'adobe/external_experienced/wd5', platform: 'workday', domain: 'adobe.com' },
  { label: 'Adyen', handle: 'adyen', platform: 'greenhouse', domain: 'adyen.com' },
  { label: 'Agoda', handle: 'agoda', platform: 'greenhouse', domain: 'agoda.com' },
  { label: 'AiPrise', handle: 'aiprise', platform: 'ashby', domain: 'aiprise.com' },
  { label: 'Airbnb', handle: 'airbnb', platform: 'greenhouse', domain: 'airbnb.com' },
  { label: 'Akamai Technologies', handle: 'fa-extu-saasfaprod1.fa.ocs.oraclecloud.com/CX_1', platform: 'oraclehcm', domain: 'akamai.com' },
  { label: 'Alteryx', handle: 'alteryx/AlteryxCareers/wd108', platform: 'workday', domain: 'alteryx.com' },
  { label: 'Amadeus', handle: 'amadeus/jobs/wd502', platform: 'workday', domain: 'amadeus.com' },
  { label: 'AMD', handle: 'careers.amd.com', platform: 'jibe', domain: 'amd.com' },
  { label: 'American Express', handle: 'egug.fa.us2.oraclecloud.com/CX_1', platform: 'oraclehcm', domain: 'americanexpress.com' },
  { label: 'Analog Devices', handle: 'analogdevices/External/wd1', platform: 'workday', domain: 'analog.com' },
  { label: 'Anthropic', handle: 'anthropic', platform: 'greenhouse', domain: 'anthropic.com' },
  { label: 'Arcana', handle: 'arcanaanalytics', platform: 'greenhouse' },
  { label: 'Arista Networks', handle: 'aristanetworks', platform: 'smartrecruiters', domain: 'arista.com' },
  { label: 'Atlan', handle: 'atlan', platform: 'ashby', domain: 'atlan.com' },
  { label: 'Atlys', handle: 'atlys', platform: 'ashby', domain: 'atlys.com' },
  { label: 'Autodesk', handle: 'autodesk.eightfold.ai/autodesk.com', platform: 'eightfold', domain: 'autodesk.com' },
  { label: 'Awfis', handle: 'awfis.keka.com', platform: 'keka', domain: 'awfis.com' },
  { label: 'Bank of America', handle: 'ghr/lateral-us/wd1', platform: 'workday', domain: 'bankofamerica.com' },
  { label: 'BlackRock', handle: 'blackrock/BlackRock_Professional/wd1', platform: 'workday', domain: 'blackrock.com' },
  { label: 'BNY Mellon', handle: 'eofe.fa.us2.oraclecloud.com/BNY-Careers', platform: 'oraclehcm', domain: 'bny.com' },
  { label: 'Booking.com', handle: 'jobs.booking.com', platform: 'jibe', domain: 'booking.com' },
  { label: 'Brightmoney', handle: 'brightmoney', platform: 'kula', domain: 'brightmoney.co' },
  { label: 'Broadcom', handle: 'broadcom/External_Career/wd1', platform: 'workday', domain: 'broadcom.com' },
  { label: 'Broadridge', handle: 'broadridge/Careers/wd5', platform: 'workday', domain: 'broadridge.com' },
  { label: 'BrowserStack', handle: 'browserstack/External/wd3', platform: 'workday', domain: 'browserstack.com' },
  { label: 'Cadence', handle: 'cadence/External_Careers/wd1', platform: 'workday', domain: 'cadence.com' },
  { label: 'Canonical', handle: 'canonical', platform: 'greenhouse', domain: 'canonical.com' },
  { label: 'Canva', handle: 'canva', platform: 'smartrecruiters', domain: 'canva.com' },
  { label: 'Capital One', handle: 'capitalone/Capital_One/wd12', platform: 'workday', domain: 'capitalone.com' },
  { label: 'Cashfree', handle: 'cashfree', platform: 'kula', domain: 'cashfree.com' },
  { label: 'Celonis', handle: 'celonis', platform: 'greenhouse', domain: 'celonis.com' },
  { label: 'Check Point', handle: 'CheckPointSoftwareTechnologies2', platform: 'smartrecruiters', domain: 'checkpoint.com' },
  { label: 'Cisco', handle: 'cisco/Cisco_Careers/wd5', platform: 'workday', domain: 'cisco.com' },
  { label: 'Citi', handle: 'citi/2/wd5', platform: 'workday', domain: 'citi.com' },
  { label: 'CleverTap', handle: 'clevertap', platform: 'kula', domain: 'clevertap.com' },
  { label: 'Cloud Software Group', handle: 'tibco/Cloud_Software_Group/wd5', platform: 'workday', domain: 'cloud.com' },
  { label: 'Cohesity', handle: 'cohesity/Cohesity_Careers/wd5', platform: 'workday', domain: 'cohesity.com' },
  { label: 'Coinbase', handle: 'coinbase', platform: 'greenhouse', domain: 'coinbase.com' },
  { label: 'Commvault', handle: 'commvault', platform: 'greenhouse', domain: 'commvault.com' },
  { label: 'Confluent', handle: 'confluent', platform: 'ashby', domain: 'confluent.io' },
  { label: 'Couchbase', handle: 'couchbaseinc', platform: 'greenhouse', domain: 'couchbase.com' },
  { label: 'Coupang', handle: 'coupang', platform: 'greenhouse', domain: 'coupang.com' },
  { label: 'CRED', handle: 'cred', platform: 'lever', domain: 'cred.club' },
  { label: 'CrowdStrike', handle: 'crowdstrike/crowdstrikecareers/wd5', platform: 'workday', domain: 'crowdstrike.com' },
  { label: 'Cultfit', handle: 'careers.cult.fit', platform: 'zwayam', domain: 'cult.fit' },
  { label: 'Databricks', handle: 'databricks', platform: 'greenhouse', domain: 'databricks.com' },
  { label: 'Datadog', handle: 'datadog', platform: 'greenhouse', domain: 'datadoghq.com' },
  { label: 'Dell', handle: 'https://enterpriseplatform.dell.com/hcmUI/CandidateExperience/en/sites/CX_1001', platform: 'oraclehcm', domain: 'dell.com' },
  { label: 'Deutsche Bank', handle: 'db/DBWebsite/wd3', platform: 'workday', domain: 'db.com' },
  { label: 'DigiCert', handle: 'digicert', platform: 'greenhouse', domain: 'digicert.com' },
  { label: 'DigitalOcean', handle: 'digitalocean98', platform: 'greenhouse', domain: 'digitalocean.com' },
  { label: 'Discord', handle: 'discord', platform: 'greenhouse', domain: 'discord.com' },
  { label: 'Docusign', handle: 'careers.docusign.com', platform: 'jibe', domain: 'docusign.com' },
  { label: 'DoorDash', handle: 'doordashindia', platform: 'greenhouse', domain: 'doordash.com' },
  { label: 'Dropbox', handle: 'dropbox', platform: 'greenhouse', domain: 'dropbox.com' },
  { label: 'Druva', handle: 'druva', platform: 'greenhouse', domain: 'druva.com' },
  { label: 'eBay', handle: 'ebay/apply/wd5', platform: 'workday', domain: 'ebay.com' },
  { label: 'Eightfold', handle: 'app.eightfold.ai/eightfold.ai', platform: 'eightfold', domain: 'eightfold.ai' },
  { label: 'Elastic', handle: 'elastic', platform: 'greenhouse', domain: 'elastic.co' },
  { label: 'Expedia', handle: 'expedia/search/wd108', platform: 'workday', domain: 'expediagroup.com' },
  { label: 'FactSet', handle: 'factset/FactSetCareers/wd108', platform: 'workday', domain: 'factset.com' },
  { label: 'FamPay', handle: 'fampay', platform: 'lever', domain: 'famapp.in' },
  { label: 'FIS', handle: 'fis/SearchJobs/wd5', platform: 'workday', domain: 'fisglobal.com' },
  { label: 'Fiserv', handle: 'fiserv/EXT/wd5', platform: 'workday', domain: 'fiserv.com' },
  { label: 'Flipkart', handle: 'flipkart.turbohire.co', platform: 'turbohire', domain: 'flipkart.com' },
  { label: 'Flynote', handle: 'flynote.freshteam.com', platform: 'freshteam', domain: 'flynote.com' },
  { label: 'Fractal', handle: 'fractal/Careers/wd1', platform: 'workday', domain: 'fractal.ai' },
  { label: 'Freshworks', handle: 'freshworks', platform: 'smartrecruiters', domain: 'freshworks.com' },
  { label: 'Gartner', handle: 'gartner/EXT/wd5', platform: 'workday', domain: 'gartner.com' },
  { label: 'GE Aerospace', handle: 'geaerospace/GE_ExternalSite/wd5', platform: 'workday', domain: 'geaerospace.com' },
  { label: 'GE HealthCare', handle: 'gehc/GEHC_ExternalSite/wd5', platform: 'workday', domain: 'gehealthcare.com' },
  { label: 'Gen Digital', handle: 'gen-digital', platform: 'ashby', domain: 'gendigital.com' },
  { label: 'GitLab', handle: 'gitlab', platform: 'greenhouse', domain: 'gitlab.com' },
  { label: 'Glance', handle: 'glance', platform: 'greenhouse', domain: 'glance.com' },
  { label: 'Glean', handle: 'glean', platform: 'smartrecruiters', domain: 'glean.com' },
  { label: 'Grab', handle: 'grab', platform: 'smartrecruiters', domain: 'grab.com' },
  { label: 'Groww', handle: 'groww', platform: 'greenhouse', domain: 'groww.in' },
  { label: 'Groww', handle: 'growwreferrals', platform: 'greenhouse', domain: 'groww.in' },
  { label: 'Haptik', handle: 'haptik.freshteam.com', platform: 'freshteam', domain: 'haptik.ai' },
  { label: 'Harness', handle: 'harnessinc', platform: 'greenhouse', domain: 'harness.io' },
  { label: 'Hevo Data', handle: 'hevodata', platform: 'lever', domain: 'hevodata.com' },
  { label: 'Hewlett Packard Enterprise', handle: 'hpe/Jobsathpe/wd5', platform: 'workday', domain: 'hpe.com' },
  { label: 'Honeywell', handle: 'ibqbjb.fa.ocs.oraclecloud.com/Honeywell', platform: 'oraclehcm', domain: 'honeywell.com' },
  { label: 'HP', handle: 'hp.eightfold.ai/hp.com', platform: 'eightfold', domain: 'hp.com' },
  { label: 'HSBC', handle: 'hsbc.eightfold.ai/hsbc.com', platform: 'eightfold', domain: 'hsbc.com' },
  { label: 'Hyperproof', handle: 'hyperproof', platform: 'greenhouse', domain: 'hyperproof.io' },
  { label: 'ICE', handle: 'careers.ice.com', platform: 'jibe', domain: 'ice.com' },
  { label: 'Icertis', handle: 'iaaviz.fa.ocs.oraclecloud.com/Jobs-at-Icertis', platform: 'oraclehcm', domain: 'icertis.com' },
  { label: 'Imply', handle: 'imply', platform: 'greenhouse', domain: 'imply.io' },
  { label: 'Infineon', handle: 'infineon.eightfold.ai/infineon.com', platform: 'eightfold', domain: 'infineon.com' },
  { label: 'InMobi', handle: 'inmobi', platform: 'greenhouse', domain: 'inmobi.com' },
  { label: 'Intel', handle: 'intel/External/wd1', platform: 'workday', domain: 'intel.com' },
  { label: 'Invesco', handle: 'invesco/IVZ/wd1', platform: 'workday', domain: 'invesco.com' },
  { label: 'Ixigo', handle: 'ixigo', platform: 'smartrecruiters', domain: 'ixigo.com' },
  { label: 'JioStar', handle: 'jiostar/JioStar/wd102', platform: 'workday', domain: 'jiostar.com' },
  { label: 'JPMorgan Chase', handle: 'jpmc.fa.oraclecloud.com/CX_1001', platform: 'oraclehcm', domain: 'jpmorganchase.com' },
  { label: 'Jupiter', handle: 'jupiter.keka.com', platform: 'keka', domain: 'jupiter.money' },
  { label: 'Khatabook', handle: 'khatabook.turbohire.co', platform: 'turbohire', domain: 'khatabook.com' },
  { label: 'Kotak Mahindra Bank', handle: 'hcbt.fa.em2.oraclecloud.com/CX', platform: 'oraclehcm', domain: 'kotak.com' },
  { label: 'LambdaTest', handle: 'lambdatest.keka.com', platform: 'keka', domain: 'lambdatest.com' },
  { label: 'Leap Finance', handle: 'leapfinance.freshteam.com', platform: 'freshteam', domain: 'leapfinance.com' },
  { label: 'Lenskart', handle: 'lenskart_ho', platform: 'ainterviews', domain: 'lenskart.com' },
  { label: 'LinkedIn', handle: 'linkedin', platform: 'lever', domain: 'linkedin.com' },
  { label: 'Log 9 Materials', handle: 'log9materials-talent.freshteam.com', platform: 'freshteam', domain: 'log9materials.com' },
  { label: "Lowe's", handle: 'lowes/LWS_External_CS/wd5', platform: 'workday', domain: 'lowes.com' },
  { label: 'LSEG', handle: 'lseg/Careers/wd3', platform: 'workday', domain: 'lseg.com' },
  { label: 'Marvell', handle: 'marvell/MarvellCareers/wd1', platform: 'workday', domain: 'marvell.com' },
  { label: 'Mastercard', handle: 'mastercard/CorporateCareers/wd1', platform: 'workday', domain: 'mastercard.com' },
  { label: 'Media.net', handle: 'medianet', platform: 'smartrecruiters', domain: 'media.net' },
  { label: 'Meesho', handle: 'meesho', platform: 'lever', domain: 'meesho.com' },
  { label: 'Mesh', handle: 'mesh.freshteam.com', platform: 'freshteam', domain: 'meshhq.com' },
  { label: 'Micron', handle: 'micron/External/wd1', platform: 'workday', domain: 'micron.com' },
  { label: 'Millennium', handle: 'mlp.eightfold.ai/mlp.com', platform: 'eightfold', domain: 'mlp.com' },
  { label: 'MindTickle', handle: 'mindtickle', platform: 'lever', domain: 'mindtickle.com' },
  { label: 'MongoDB', handle: 'mongodb', platform: 'greenhouse', domain: 'mongodb.com' },
  { label: 'Morgan Stanley', handle: 'morganstanley.eightfold.ai/morganstanley.com', platform: 'eightfold', domain: 'morganstanley.com' },
  { label: 'Morningstar', handle: 'morningstar/Morningstar/wd5', platform: 'workday', domain: 'morningstar.com' },
  { label: 'Multiplier', handle: 'usemultiplier', platform: 'kula', domain: 'usemultiplier.com' },
  { label: 'Nasdaq', handle: 'nasdaq/Global_External_Site/wd1', platform: 'workday', domain: 'nasdaq.com' },
  { label: 'Navi', handle: 'navi', platform: 'ashby', domain: 'navi.com' },
  { label: 'NetApp', handle: 'netapp.eightfold.ai/netapp.com', platform: 'eightfold', domain: 'netapp.com' },
  { label: 'Netflix', handle: 'explore.jobs.netflix.net/netflix.com', platform: 'eightfold', domain: 'netflix.com' },
  { label: 'Netomi', handle: 'netomi', platform: 'lever', domain: 'netomi.com' },
  { label: 'Nike', handle: 'nike/nke/wd1', platform: 'workday', domain: 'nike.com' },
  { label: 'Nium', handle: 'nium', platform: 'lever', domain: 'nium.com' },
  { label: 'Northern Trust', handle: 'ntrs/northerntrust/wd1', platform: 'workday', domain: 'northerntrust.com' },
  { label: 'Notion', handle: 'notion', platform: 'ashby', domain: 'notion.so' },
  { label: 'Nvidia', handle: 'nvidia/NVIDIAExternalCareerSite/wd5', platform: 'workday', domain: 'nvidia.com' },
  { label: 'NXP', handle: 'nxp/Careers/wd3', platform: 'workday', domain: 'nxp.com' },
  { label: 'Observe.AI', handle: 'observeai', platform: 'greenhouse', domain: 'observe.ai' },
  { label: 'Okta', handle: 'okta', platform: 'greenhouse', domain: 'okta.com' },
  { label: 'OpenAI', handle: 'openai', platform: 'ashby', domain: 'openai.com' },
  { label: 'Oracle', handle: 'eeho.fa.us2.oraclecloud.com/CX_45001', platform: 'oraclehcm', domain: 'oracle.com' },
  { label: 'Palo Alto Networks', handle: 'paloaltonetworks/panwexternalcareers/wd5', platform: 'workday', domain: 'paloaltonetworks.com' },
  { label: 'PayPal', handle: 'paypal.eightfold.ai/paypal.com', platform: 'eightfold', domain: 'paypal.com' },
  { label: 'Paytm', handle: 'paytm', platform: 'lever', domain: 'paytm.com' },
  { label: 'Philips', handle: 'philips/jobs-and-careers/wd3', platform: 'workday', domain: 'philips.com' },
  { label: 'PhonePe', handle: 'PHONEPELIMITED', platform: 'smartrecruiters', domain: 'phonepe.com' },
  { label: 'Plum', handle: 'plumhq', platform: 'kula', domain: 'plumhq.com' },
  { label: 'Postman', handle: 'postman/careers/wd108', platform: 'workday', domain: 'postman.com' },
  { label: 'Project44', handle: 'project44', platform: 'greenhouse', domain: 'project44.com' },
  { label: 'PTC', handle: 'ptc/PTC/wd1', platform: 'workday', domain: 'ptc.com' },
  { label: 'Pure Storage', handle: 'purestorage', platform: 'ashby', domain: 'purestorage.com' },
  { label: 'Purplle', handle: 'purplle.turbohire.co', platform: 'turbohire', domain: 'purplle.com' },
  { label: 'Q2', handle: 'q2ebanking/Q2/wd5', platform: 'workday', domain: 'q2.com' },
  { label: 'Qualcomm', handle: 'careers.qualcomm.com/qualcomm.com', platform: 'eightfold', domain: 'qualcomm.com' },
  { label: 'Qualys', handle: 'qualys/Careers/wd5', platform: 'workday', domain: 'qualys.com' },
  { label: 'Quizizz', handle: 'Wayground', platform: 'lever', domain: 'quizizz.com' },
  { label: 'Rapid7', handle: 'mymoose/careers/wd1', platform: 'workday', domain: 'rapid7.com' },
  { label: 'Razorpay', handle: 'razorpaysoftwareprivatelimited', platform: 'greenhouse', domain: 'razorpay.com' },
  { label: 'Red Hat', handle: 'redhat/jobs/wd5', platform: 'workday', domain: 'redhat.com' },
  { label: 'Redis', handle: 'redis', platform: 'ashby', domain: 'redis.io' },
  { label: 'Rippling', handle: 'rippling', platform: 'rippling', domain: 'rippling.com' },
  { label: 'Roblox', handle: 'roblox', platform: 'greenhouse', domain: 'roblox.com' },
  { label: 'Rocketlane', handle: 'rocketlane', platform: 'kula', domain: 'rocketlane.com' },
  { label: 'Roku', handle: 'roku', platform: 'greenhouse', domain: 'roku.com' },
  { label: 'Rubrik', handle: 'rubrik', platform: 'greenhouse', domain: 'rubrik.com' },
  { label: 'S&P Global', handle: 'spgi/SPGI_Careers/wd5', platform: 'workday', domain: 'spglobal.com' },
  { label: 'SaaS Labs', handle: 'saas-labs', platform: 'kula', domain: 'saaslabs.co' },
  { label: 'Samsung', handle: 'sec/Samsung_Careers/wd3', platform: 'workday', domain: 'samsung.com' },
  { label: 'Sarvam AI', handle: 'sarvam', platform: 'ashby', domain: 'sarvam.ai' },
  { label: 'ServiceNow', handle: 'servicenow', platform: 'smartrecruiters', domain: 'servicenow.com' },
  { label: 'Sigmoid', handle: 'sigmoid', platform: 'greenhouse', domain: 'sigmoid.com' },
  { label: 'SigNoz', handle: 'signoz', platform: 'ashby', domain: 'signoz.io' },
  { label: 'Slice', handle: 'slice', platform: 'greenhouse', domain: 'sliceit.com' },
  { label: 'Snowflake', handle: 'snowflake', platform: 'ashby', domain: 'snowflake.com' },
  { label: 'Spotify', handle: 'spotify', platform: 'lever', domain: 'spotify.com' },
  { label: 'Sprinklr', handle: 'sprinklr/careers/wd1', platform: 'workday', domain: 'sprinklr.com' },
  { label: 'Sprinto', handle: 'Sprinto', platform: 'lever', domain: 'sprinto.com' },
  { label: 'State Street', handle: 'statestreet/Global/wd1', platform: 'workday', domain: 'statestreet.com' },
  { label: 'Stripe', handle: 'stripe', platform: 'greenhouse', domain: 'stripe.com' },
  { label: 'Swiggy', handle: 'swiggy', platform: 'smartrecruiters', domain: 'swiggy.com' },
  { label: 'Synchrony', handle: 'synchronyfinancial/careers/wd5', platform: 'workday', domain: 'synchrony.com' },
  { label: 'Target', handle: 'target/targetcareers/wd5', platform: 'workday', domain: 'target.com' },
  { label: 'Tekion', handle: 'tekion', platform: 'ashby', domain: 'tekion.com' },
  { label: 'Termgrid', handle: 'termgrid', platform: 'lever', domain: 'termgrid.com' },
  { label: 'Texas Instruments', handle: 'edbz.fa.us2.oraclecloud.com/CX', platform: 'oraclehcm', domain: 'ti.com' },
  { label: 'Thomson Reuters', handle: 'thomsonreuters/External_Career_Site/wd5', platform: 'workday', domain: 'thomsonreuters.com' },
  { label: 'ThoughtSpot', handle: 'thoughtspot', platform: 'rippling', domain: 'thoughtspot.com' },
  { label: 'Thoughtworks', handle: 'thoughtworks', platform: 'greenhouse', domain: 'thoughtworks.com' },
  { label: 'Toast', handle: 'toast', platform: 'greenhouse', domain: 'toasttab.com' },
  { label: 'Tower Research Capital', handle: 'towerresearchcapital', platform: 'greenhouse', domain: 'tower-research.com' },
  { label: 'Trellix', handle: 'trellix/EnterpriseCareers/wd1', platform: 'workday', domain: 'trellix.com' },
  { label: 'Truecaller', handle: 'truecaller', platform: 'greenhouse', domain: 'truecaller.com' },
  { label: 'Twilio', handle: 'twilio', platform: 'greenhouse', domain: 'twilio.com' },
  { label: 'Uber', handle: 'uber', platform: 'smartrecruiters', domain: 'uber.com' },
  { label: 'Ubisoft', handle: 'Ubisoft2', platform: 'smartrecruiters', domain: 'ubisoft.com' },
  { label: 'UiPath', handle: 'uipath', platform: 'ashby', domain: 'uipath.com' },
  { label: 'Unacademy', handle: 'unacademy', platform: 'smartrecruiters', domain: 'unacademy.com' },
  { label: 'Uniphore', handle: 'uniphore/Uniphore/wd503', platform: 'workday', domain: 'uniphore.com' },
  { label: 'Unity', handle: 'unitytech/Unity/wd1', platform: 'workday', domain: 'unity.com' },
  { label: 'Urban Company', handle: 'urbancompany.turbohire.co', platform: 'turbohire', domain: 'urbancompany.com' },
  { label: 'Vanguard', handle: 'vanguard/Vanguard_External/wd5', platform: 'workday', domain: 'vanguard.com' },
  { label: 'Visa', handle: 'visa/Visa/wd5', platform: 'workday', domain: 'visa.com' },
  { label: 'Walmart', handle: 'walmart/WalmartExternal/wd504', platform: 'workday', domain: 'walmart.com' },
  { label: 'WebEngage', handle: 'webklipper.keka.com', platform: 'keka', domain: 'webengage.com' },
  { label: 'Wise', handle: 'wise', platform: 'smartrecruiters', domain: 'wise.com' },
  { label: 'Wolters Kluwer', handle: 'wk/External/wd3', platform: 'workday', domain: 'wolterskluwer.com' },
  { label: 'Workday', handle: 'workday/Workday/wd5', platform: 'workday', domain: 'workday.com' },
  { label: 'Worldpay', handle: 'worldpay/Worldpay_External_Careers_Site/wd5', platform: 'workday', domain: 'worldpay.com' },
  { label: 'WorldQuant', handle: 'worldquant', platform: 'greenhouse', domain: 'worldquant.com' },
  { label: 'YugabyteDB', handle: 'yugabyte', platform: 'greenhouse', domain: 'yugabyte.com' },
  { label: 'Zendesk', handle: 'zendesk/zendesk/wd1', platform: 'workday', domain: 'zendesk.com' },
  { label: 'Zeta', handle: 'zeta', platform: 'lever', domain: 'zeta.tech' },
  { label: 'Zluri', handle: 'zluri.keka.com', platform: 'keka', domain: 'zluri.com' },
  { label: 'Zomato', handle: 'Zomato1', platform: 'smartrecruiters', domain: 'zomato.com' },
  { label: 'ZS Associates', handle: 'jobs.zs.com', platform: 'jibe', domain: 'zs.com' },
  { label: 'Zscaler', handle: 'zscaler', platform: 'greenhouse', domain: 'zscaler.com' },
  { label: 'Zynga', handle: 'zyngacareers', platform: 'greenhouse', domain: 'zynga.com' },
]

export function knownCompany(handle: string): KnownCompany | undefined {
  const needle = handle.trim().toLowerCase()
  return knownCompanies.find((c) => c.handle.toLowerCase() === needle)
}

const normalize = (name: string) => name.toLowerCase().replace(/[^a-z0-9]/g, '')

/**
 * A posting's `metadata.company` is the user's label, the board's own name or, failing both, a bare
 * handle segment (`CX_1001`); each is tried against the known list.
 */
export function companyFor(name: string | null | undefined): KnownCompany | undefined {
  if (!name) return undefined
  const needle = normalize(name)
  if (!needle) return undefined
  const exact = knownCompanies.find(
    (c) => normalize(c.label) === needle || normalize(c.handle) === needle,
  )
  if (exact) return exact
  // A segment is only trusted when it is unique: two Oracle tenants both end in CX_1.
  const bySegment = knownCompanies.filter((c) => {
    const segments = c.handle.split('/')
    return normalize(segments[0]) === needle || normalize(segments[segments.length - 1]) === needle
  })
  return bySegment.length === 1 ? bySegment[0] : undefined
}

/**
 * Loaded by the browser straight from DuckDuckGo, so it sees which domains are looked up. It answers
 * 404 for an unknown domain, which is what lets the icon fall back to a monogram.
 */
export function companyLogoUrl(domain: string): string {
  return `https://icons.duckduckgo.com/ip3/${encodeURIComponent(domain)}.ico`
}

export function withCompanyLabels(
  inputs: Record<string, unknown>,
  previous?: Record<string, unknown>,
): Record<string, unknown> {
  const companies = Array.isArray(inputs.companies) ? (inputs.companies as string[]) : []
  const kept = (previous?.companyLabels ?? {}) as Record<string, unknown>
  const companyLabels: Record<string, string> = {}
  for (const company of companies) {
    const label =
      knownCompany(company)?.label ??
      knownCompany(company.slice(company.indexOf(':') + 1))?.label ??
      (typeof kept[company] === 'string' ? (kept[company] as string) : undefined)
    if (label) companyLabels[company] = label
  }
  const out = { ...inputs }
  delete out.companyLabels
  if (Object.keys(companyLabels).length > 0) out.companyLabels = companyLabels
  return out
}
